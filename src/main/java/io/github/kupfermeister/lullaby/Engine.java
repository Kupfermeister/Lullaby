package io.github.kupfermeister.lullaby;

import io.github.kupfermeister.lullaby.error.ErrorReporter;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.lua.LuaRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Holds everything one load of the scripts needs: the Lua state, the event listeners and the error reporter, and the
 * files in plugins/lullaby. A reload stops every task (their finals run), throws the Lua state and all listeners
 * away and builds them new; script data will live in Java and survive that. Main thread only.
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
    private LuaRuntime runtime;

    Engine(Lullaby plugin) {
        this.plugin = plugin;
        this.dataFolder = plugin.getDataPath();
        this.scriptsFolder = dataFolder.resolve("scripts");
        this.errors = new ErrorReporter(plugin.getComponentLogger());
        this.events = new EventBridge(this);
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

    /** Loads (or reloads) every script. On a reload, the old state's tasks are stopped first. */
    public LoadResult load(boolean reload) {
        long start = System.nanoTime();
        unload();
        errors.resetCount();
        int scripts = 0;
        try {
            runtime = new LuaRuntime(this);
            scripts = runtime.loadAll();
            if (reload) runtime.fireReload();
        } catch (IOException | RuntimeException e) {
            internalError("loading the scripts", e);
        }
        return new LoadResult(scripts, errors.count(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Stops every task (finals run), removes the listeners and closes the Lua state. */
    void unload() {
        events.unregisterAll();
        if (runtime == null) return;
        try {
            runtime.stopAllTasks();
        } catch (RuntimeException e) {
            internalError("stopping the tasks", e);
        }
        runtime.close();
        runtime = null;
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

    public void dispatch(String name, String kind, String stage, Object event) {
        if (runtime == null) return;
        try {
            runtime.dispatch(name, kind, stage, event);
        } catch (RuntimeException e) {
            internalError("the event " + name, e);
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
