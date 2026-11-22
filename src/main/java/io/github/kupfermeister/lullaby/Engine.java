package io.github.kupfermeister.lullaby;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent;
import io.github.kupfermeister.lullaby.error.ErrorReporter;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.lua.LuaRuntime;
import io.github.kupfermeister.lullaby.lua.Watchdog;
import io.github.kupfermeister.lullaby.nms.Input;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.SavedData;
import io.github.kupfermeister.lullaby.nms.ScriptCommands;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.IllegalPluginAccessException;

/**
 * Holds everything one load of the scripts needs: the Lua state, the event listeners and the error reporter, and the
 * files in plugins/lullaby. A reload stops every task (their finals run), throws the Lua state and all listeners
 * away and builds them new; server.data is saved first and loaded into the new state (see {@link SavedData}). Main
 * thread only.
 */
public final class Engine {

    /** What a load did, for the reload message. */
    public record LoadResult(int scripts, int errors, long millis) {
    }

    private final Lullaby plugin;
    private final Path dataFolder;
    private final Path scriptsFolder;
    private final ErrorReporter errors;
    private final EventBridge events;
    private final Watchdog watchdog; // script_time_limit; lives as long as the plugin is on
    private LuaRuntime runtime;
    private volatile ScriptCommands commands; // volatile: also read on Paper's tree-building threads
    // Owns the join/quit listeners for script commands; registered only while scripts declare commands.
    private final Listener commandPlayers = new Listener() {
    };
    private final Listener saves = new Listener() { // WorldSaveEvent, for the plugin's whole life
    };
    private final SavedData data; // server.data's file; lives as long as the plugin, so it outlasts reloads

    Engine(Lullaby plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataPath();
        this.scriptsFolder = dataFolder.resolve("scripts");
        this.errors = new ErrorReporter(plugin.getComponentLogger());
        this.events = new EventBridge(this);
        this.watchdog = new Watchdog(plugin.getSLF4JLogger());
        this.data = new SavedData(dataFolder,
                (kind, via, message) -> errors.report(kind, SavedData.FILE_TEXT, -1, via, message),
                task -> {
                    try {
                        Bukkit.getScheduler().runTask(plugin, task);
                    } catch (IllegalPluginAccessException e) {
                        task.run(); // the plugin is turning off: nothing else runs on the main thread any more
                    }
                });
        listenForSaves();
    }

    public SavedData data() {
        return data;
    }

    /**
     * server.data is saved when the world is: Paper fires WorldSaveEvent once per world at each autosave, /save-all
     * and stop, so only the main world's counts. (At stop, plugins turn off before the worlds save: {@link #unload}
     * saves then.) Registered once for the plugin's life.
     */
    private void listenForSaves() {
        Bukkit.getPluginManager().registerEvent(WorldSaveEvent.class, saves, EventPriority.MONITOR, (listener, e) -> {
            if (e instanceof WorldSaveEvent save && !Bukkit.getWorlds().isEmpty()
                    && save.getWorld().equals(Bukkit.getWorlds().getFirst())) {
                saveData(false, false);
            }
        }, plugin);
    }

    /**
     * Saves server.data unless a hand edit or a bad load forbids it (then says why). {@code wait}: write the file
     * now; {@code reloading}: a load follows at once.
     */
    private void saveData(boolean wait, boolean reloading) {
        if (runtime == null) return;
        try {
            String refusal = data.refusal(reloading);
            if (refusal != null) {
                errors.report("warning", SavedData.FILE_TEXT, -1, "saving server.data", refusal);
                return;
            }
            runtime.saveData(data, wait);
        } catch (RuntimeException e) {
            internalError("saving server.data", e);
        }
    }

    /** /lullaby data server [path]: the view and the place's name; view null: nothing there (or no scripts loaded). */
    public Object[] viewData(String path) {
        if (runtime == null) return new Object[]{null, "server.data"};
        return runtime.dataView(path);
    }

    /** Tab completion of /lullaby data server: the keys at one place. Main thread. */
    public List<String> dataKeys(String path) {
        if (runtime == null) return List.of();
        try {
            return runtime.dataKeys(path);
        } catch (RuntimeException e) {
            internalError("listing server.data keys", e);
            return List.of();
        }
    }

    public Lullaby plugin() {
        return plugin;
    }

    public ErrorReporter errors() {
        return errors;
    }

    public EventBridge events() {
        return events;
    }

    public Watchdog watchdog() {
        return watchdog;
    }

    /** This load's script commands; null between loads. */
    public ScriptCommands commands() {
        return commands;
    }

    /** First start: copies config.lua and scripts/lullaby.lua if they are missing. */
    void createDefaults() throws IOException {
        Files.createDirectories(dataFolder);
        copyDefault("/defaults/config.lua", dataFolder.resolve("config.lua"));
        if (!Files.exists(scriptsFolder)) {
            Files.createDirectories(scriptsFolder);
            copyDefault("/defaults/lullaby.lua", scriptsFolder.resolve("lullaby.lua"));
        }
    }

    private static void copyDefault(String resource, Path target) throws IOException {
        if (Files.exists(target)) return;
        try (InputStream in = Engine.class.getResourceAsStream(resource)) {
            Files.copy(in, target);
        }
    }

    /** Loads (or reloads) every script; on a reload, the old state's tasks are stopped first. See {@link #afterReload}. */
    public LoadResult load() {
        long start = System.nanoTime();
        unload(true);
        errors.resetCount();
        int scripts = 0;
        try {
            commands = new ScriptCommands(this::runCommand);
            listenForCommandPlayers(); // before the scripts, so it runs before their own lowest-stage join handlers
            runtime = new LuaRuntime(this);
            runtime.loadData(data); // server.data is there before the first script line runs
            scripts = runtime.loadAll();
        } catch (IOException | RuntimeException e) {
            internalError("loading the scripts", e);
        }
        return new LoadResult(scripts, errors.count(), (System.nanoTime() - start) / 1_000_000);
    }

    /**
     * After /lullaby reload has sent its summary: every online player gets the new script commands (also when the
     * load failed, so old commands vanish from their screens), then the reload event runs. {@code player}: who
     * reloaded (null: the console).
     */
    public void afterReload(LoadResult result, UUID player) {
        try {
            if (runtime == null) {
                Nms.resendCommands();
                return;
            }
            for (String uuid : Nms.onlinePlayers()) runtime.refreshCommands(uuid);
            runtime.fireReload(result.scripts(), result.errors(), result.millis(), player);
        } catch (RuntimeException e) {
            internalError("finishing the reload", e);
        }
    }

    /**
     * Join: vanilla sends a joining player's command tree while they log in, before any script can say what they see,
     * so the tree is worked out and sent again at the lowest join stage. Quit: the player's snapshot is dropped.
     * Data pack reload (/minecraft:reload): vanilla builds a new dispatcher and only Bukkit commands move over, so the
     * script commands are put back and every tree is resent. All three do nothing while no script declares commands.
     */
    private void listenForCommandPlayers() {
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, commandPlayers, EventPriority.LOWEST,
                (listener, e) -> {
                    if (e instanceof PlayerJoinEvent join && runtime != null && !commands.isEmpty()) {
                        try {
                            runtime.refreshCommands(join.getPlayer().getUniqueId().toString());
                        } catch (RuntimeException ex) {
                            internalError("working out a player's commands", ex);
                        }
                    }
                }, plugin);
        Bukkit.getPluginManager().registerEvent(PlayerQuitEvent.class, commandPlayers, EventPriority.MONITOR,
                (listener, e) -> {
                    if (e instanceof PlayerQuitEvent quit && commands != null) {
                        commands.forget(quit.getPlayer().getUniqueId());
                    }
                }, plugin);
        // Fires on a tree-building thread and again on the main thread right before sending (ScriptCommands.treeEvent
        // acts only on the main-thread one). No Lua here.
        Bukkit.getPluginManager().registerEvent(AsyncPlayerSendCommandsEvent.class, commandPlayers,
                EventPriority.MONITOR, (listener, e) -> {
                    ScriptCommands current = commands;
                    if (e instanceof AsyncPlayerSendCommandsEvent<?> sent && current != null) {
                        current.treeEvent(sent.getPlayer().getUniqueId(), sent.getCommandNode(), sent.hasFiredAsync());
                    }
                }, plugin);
        Bukkit.getPluginManager().registerEvent(ServerResourcesReloadedEvent.class, commandPlayers,
                EventPriority.MONITOR, (listener, e) -> {
                    if (commands != null && !commands.isEmpty()) {
                        commands.reattach();
                        commands.resendAll();
                    }
                }, plugin);
    }

    /** A script command was typed (Brigadier calls this on the main thread). */
    private void runCommand(int node, UUID player, List<Object> args) {
        if (runtime == null) return;
        try {
            runtime.runCommand(node, player, args);
        } catch (RuntimeException e) {
            internalError("a command", e);
        }
    }

    /**
     * Stops every task (finals run, and may still use commands), saves server.data (after the finals, which may change
     * it), removes the listeners and commands and closes the Lua state. {@code reloading}: a load follows (false: the
     * plugin turns off).
     */
    void unload(boolean reloading) {
        events.unregisterAll();
        Input.stop();
        HandlerList.unregisterAll(commandPlayers);
        if (runtime != null) {
            try {
                runtime.stopAllTasks();
            } catch (RuntimeException e) {
                internalError("stopping the tasks", e);
            }
            saveData(true, reloading);
            runtime.close();
            runtime = null;
        }
        if (commands != null) {
            commands.clear();
            commands = null;
        }
    }

    void tick() {
        if (runtime != null) {
            try {
                runtime.tick();
            } catch (RuntimeException e) {
                internalError("a tick", e);
            }
        }
        errors.tick();
    }

    /** One event for one registered listener (its number from {@link EventBridge#listen}). */
    public void dispatch(int listener, Object event) {
        if (runtime == null) return;
        try {
            runtime.dispatch(listener, event);
        } catch (RuntimeException e) {
            internalError("the event " + events.listenerName(listener), e);
        }
    }

    /**
     * Turns Lullaby's own input event on (the library calls this for the first on handler, and with {@code after}
     * true for the first after handler). A reload turns it off again (see {@link #unload}).
     */
    public void listenInput(boolean after) {
        Input.listen(plugin, this::dispatchInput, after);
    }

    /** One input packet for the scripts; true if an on handler cancelled it. A Lullaby bug never cancels. */
    private boolean dispatchInput(boolean after, Input.Event event) {
        if (runtime == null) return false;
        try {
            return runtime.dispatchInput(after, event);
        } catch (RuntimeException e) {
            internalError("the input event", e);
            return false;
        }
    }

    /** A problem inside Lullaby itself (not in a script): always a bug to fix. */
    private void internalError(String during, Exception e) {
        // FORMAT: internal error
        plugin.getSLF4JLogger().error("Lullaby internal error during {} (a bug in Lullaby, not in your scripts)", during, e);
    }

    // ---- files ----

    /** config.lua's text, or null if it does not exist. */
    public String readConfig() {
        Path config = dataFolder.resolve("config.lua");
        try {
            return Files.exists(config) ? Files.readString(config, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every script path ("lobby/join.lua", "/" between folders) in load order: alphabetical (ignoring case), without
     * files and folders whose name starts with "--" (standby).
     */
    public List<String> listScripts() {
        if (!Files.isDirectory(scriptsFolder)) return List.of();
        try (Stream<Path> files = Files.walk(scriptsFolder)) {
            return files.filter(Files::isRegularFile)
                    .map(scriptsFolder::relativize)
                    .filter(path -> path.toString().endsWith(".lua"))
                    .filter(path -> {
                        for (Path part : path) if (part.toString().startsWith("--")) return false;
                        return true;
                    })
                    .map(path -> path.toString().replace('\\', '/'))
                    .sorted(Comparator.comparing((String path) -> path.toLowerCase(Locale.ROOT))
                            .thenComparing(Comparator.naturalOrder()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A script's text; the path must stay inside the scripts folder. */
    public String readScript(String path) {
        Path file = scriptsFolder.resolve(path).normalize();
        if (!file.startsWith(scriptsFolder)) throw new IllegalArgumentException("outside the scripts folder: " + path);
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
