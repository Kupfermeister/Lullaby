package io.github.kupfermeister.lullaby;

import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent;
import io.github.kupfermeister.lullaby.error.ErrorReporter;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.lua.LuaRuntime;
import io.github.kupfermeister.lullaby.lua.Watchdog;
import io.github.kupfermeister.lullaby.nms.ChunkData;
import io.github.kupfermeister.lullaby.nms.Input;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.SavedData;
import io.github.kupfermeister.lullaby.nms.ScriptCommands;
import io.github.kupfermeister.lullaby.nms.Worlds;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
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
    private final Listener saves = new Listener() { // WorldSaveEvent and WorldUnloadEvent, for the plugin's whole life
    };
    // ChunkUnloadEvent: only from the first c.data a script reads until the next reload, so unused it costs nothing
    private final Listener chunkUnloads = new Listener() {
    };
    private boolean listeningChunks;
    // One writer thread for every saved data file (written there after an autosave); lives as long as the plugin.
    private final ExecutorService dataWriter = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Lullaby data writer");
        thread.setDaemon(true);
        return thread;
    });
    private final SavedData.Reporter dataReporter;
    private final Consumer<Runnable> onMainThread;
    private final SavedData data; // server.data's file; lives as long as the plugin, so it outlasts reloads
    // w.data files, one per world, made on the first read; kept for the plugin's life (each remembers its file's state)
    private final Map<ResourceKey<Level>, SavedData> worldFiles = new HashMap<>();
    private final Set<ResourceKey<Level>> worldsRead = new HashSet<>(); // this load: worlds whose w.data a script read
    private final Set<ResourceKey<Level>> unloading = new HashSet<>(); // between WorldUnloadEvent and the next tick
    private final ChunkData chunks; // c.data inside the chunks; reset at every reload
    private final Worlds worlds; // world folders; lives as long as the plugin, so a copy outlasts a reload

    Engine(Lullaby plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataPath();
        this.scriptsFolder = dataFolder.resolve("scripts");
        this.errors = new ErrorReporter(plugin.getComponentLogger());
        this.events = new EventBridge(this);
        this.watchdog = new Watchdog(plugin.getSLF4JLogger());
        this.dataReporter = (kind, file, via, message) -> errors.report(kind, file, -1, via, message);
        this.onMainThread = task -> {
            try {
                Bukkit.getScheduler().runTask(plugin, task);
            } catch (IllegalPluginAccessException e) {
                task.run(); // the plugin is turning off: nothing else runs on the main thread any more
            }
        };
        this.data = new SavedData(dataFolder.resolve("data").resolve("server.snbt"), "plugins/lullaby/data/server.snbt",
                "server.data", dataReporter, onMainThread, dataWriter);
        this.chunks = new ChunkData(dataReporter, this::listenForChunkUnloads);
        listenForSaves();
        this.worlds = new Worlds(dataFolder.resolve("temp"));
    }

    public Worlds worlds() {
        return worlds;
    }

    public ChunkData chunks() {
        return chunks;
    }

    /** Plugin off (after {@link #unload}): waits for the last file writes and stops the writer thread. */
    void closeData() {
        data.waitForWrite();
        worldFiles.values().forEach(SavedData::waitForWrite);
        dataWriter.shutdown();
    }

    /**
     * Saved data is saved when the world is: Paper fires WorldSaveEvent once per world at each autosave, /save-all
     * and stop. server.data goes with the main world, w.data and c.data with their own world. (At stop, plugins turn
     * off before the worlds save: {@link #unload} saves then.) A world that unloads: Paper fires WorldUnloadEvent
     * first and then saves it (only if the unload asks for saving, which then fires WorldSaveEvent), so at the unload
     * event every c.data of it goes into its chunks; the tick after, its tables are locked. Registered once for the
     * plugin's life.
     */
    private void listenForSaves() {
        Bukkit.getPluginManager().registerEvent(WorldSaveEvent.class, saves, EventPriority.MONITOR, (listener, e) -> {
            if (!(e instanceof WorldSaveEvent save)) return;
            if (!Bukkit.getWorlds().isEmpty() && save.getWorld().equals(Bukkit.getWorlds().getFirst())) {
                saveData(false, false);
            }
            ResourceKey<Level> world = ((CraftWorld) save.getWorld()).getHandle().dimension();
            boolean going = unloading.contains(world);
            saveWorld(world, going, going, false);
        }, plugin);
        Bukkit.getPluginManager().registerEvent(WorldUnloadEvent.class, saves, EventPriority.MONITOR, (listener, e) -> {
            if (!(e instanceof WorldUnloadEvent unload) || runtime == null) return;
            ResourceKey<Level> world = ((CraftWorld) unload.getWorld()).getHandle().dimension();
            unloading.add(world);
            saveChunks(world, true); // into the chunks now: Paper saves them right after (if the unload saves)
            Bukkit.getScheduler().runTask(plugin, () -> {
                unloading.remove(world);
                if (MinecraftServer.getServer().getLevel(world) == null) worldGone(world);
            });
        }, plugin, true);
    }

    /** c.data needs to know when a chunk it read unloads; turned on by the first read (ChunkData). */
    private void listenForChunkUnloads() {
        if (listeningChunks) return;
        listeningChunks = true;
        Bukkit.getPluginManager().registerEvent(ChunkUnloadEvent.class, chunkUnloads, EventPriority.MONITOR,
                (listener, e) -> {
                    if (!(e instanceof ChunkUnloadEvent unload) || runtime == null) return;
                    ResourceKey<Level> world = ((CraftWorld) unload.getWorld()).getHandle().dimension();
                    int x = unload.getChunk().getX(), z = unload.getChunk().getZ();
                    if (!chunks.isAttached(world, x, z)) return;
                    try {
                        runtime.chunkUnloaded(chunks, world, x, z,
                                (CraftPersistentDataContainer) unload.getChunk().getPersistentDataContainer());
                    } catch (RuntimeException ex) {
                        internalError("saving " + ChunkData.name(world, x, z), ex);
                    }
                }, plugin);
    }

    /**
     * One world's w.data (if a script read it) and c.data. {@code wait}: write the file now; {@code now}: a changed
     * table of an unloaded chunk loads the chunk at once; {@code reloading}: a load follows.
     */
    private void saveWorld(ResourceKey<Level> world, boolean wait, boolean now, boolean reloading) {
        if (runtime == null) return;
        SavedData file = worldFiles.get(world);
        if (file != null && worldsRead.contains(world)) {
            try {
                String refusal = file.refusal(reloading);
                if (refusal != null) {
                    errors.report("warning", file.fileText(), -1, "saving " + file.name(), refusal);
                } else {
                    runtime.saveWorldData(world.identifier().toString(), file, wait);
                }
            } catch (RuntimeException e) {
                internalError("saving " + file.name(), e);
            }
        }
        saveChunks(world, now);
    }

    private void saveChunks(ResourceKey<Level> world, boolean now) {
        if (runtime == null || !chunks.uses(world)) return;
        try {
            runtime.saveChunkData(chunks, world, now);
        } catch (RuntimeException e) {
            internalError("saving the chunk data of " + world.identifier(), e);
        }
    }

    /** A world unloaded (the tick after its unload event): its w.data and c.data tables are locked and forgotten. */
    private void worldGone(ResourceKey<Level> world) {
        worldsRead.remove(world);
        chunks.forget(world);
        if (runtime == null) return;
        try {
            runtime.worldGone(world.identifier().toString());
        } catch (RuntimeException e) {
            internalError("a world unloading", e);
        }
    }

    /** w.data read from its file: {false} if the world isn't loaded, else {true, wire bytes or null}. */
    public Object[] readWorldData(Object key, ToIntFunction<Object> addSlot) {
        @SuppressWarnings("unchecked")
        ResourceKey<Level> world = (ResourceKey<Level>) key;
        if (MinecraftServer.getServer().getLevel(world) == null) return new Object[]{false};
        SavedData file = worldFiles.computeIfAbsent(world, w -> {
            Path path = Nms.worldDataFile(w);
            return new SavedData(path, Nms.shownPath(path), "world(\"" + w.identifier() + "\").data", dataReporter,
                    onMainThread, dataWriter);
        });
        worldsRead.add(world);
        return new Object[]{true, file.read(addSlot)};
    }

    /** What data.lua couldn't make again from a world's file (one per line, "" if none). */
    public void worldDataLoaded(Object key, String problems) {
        SavedData file = worldFiles.get(key);
        if (file != null) file.loaded(problems);
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
                errors.report("warning", data.fileText(), -1, "saving server.data", refusal);
                return;
            }
            runtime.saveData(data, wait);
        } catch (RuntimeException e) {
            internalError("saving server.data", e);
        }
    }

    /**
     * /lullaby data: the view and the place's name; view null: nothing there (or no scripts loaded). {@code which}:
     * "server", "world <id>" or "chunk <id> <x> <z>".
     */
    public Object[] viewData(String which, String path) {
        if (runtime == null) return new Object[]{null, "the data (no scripts are loaded)"}; // FORMAT
        return runtime.dataView(which, path);
    }

    /** Tab completion of /lullaby data: the keys at one place. Main thread. */
    public List<String> dataKeys(String which, String path) {
        if (runtime == null) return List.of();
        try {
            return runtime.dataKeys(which, path);
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
            // every loaded world's w.data and c.data, written now (changed tables of unloaded chunks load them)
            for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) {
                saveWorld(level.dimension(), true, true, reloading);
            }
            runtime.close();
            runtime = null;
        }
        // The new Lua state reads w.data and c.data again from the files and chunks.
        chunks.reset();
        worldsRead.clear();
        unloading.clear();
        HandlerList.unregisterAll(chunkUnloads);
        listeningChunks = false;
        worlds.forgetJobs();
        if (commands != null) {
            commands.clear();
            commands = null;
        }
    }

    void tick() {
        worlds.tick(); // before the scripts, so a task waiting for a copy wakes in the tick it finished
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
