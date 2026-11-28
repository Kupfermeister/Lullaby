package io.github.kupfermeister.lullaby;

import io.github.kupfermeister.lullaby.lullablend.LullaBlend;
import io.github.kupfermeister.lullaby.nms.BlockEngine;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.io.IOException;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Paper creates this class (named in paper-plugin.yml) and calls onEnable/onDisable on the main
 * thread. Without scripts Lullaby changes nothing in the game: it only listens to events scripts use.
 */
public final class Lullaby extends JavaPlugin {

    // The per-tick job is added to vanilla's list once per server run and can't be removed, so it reads this field.
    private static volatile Engine tickTarget;
    private static boolean tickRegistered;
    private static volatile boolean blocksOn; // the block engine and LullaBlend run (false after the plugin stops)

    private Engine engine;

    Engine engine() {
        return engine;
    }

    private boolean versionOk() {
        return stripZeros(getPluginMeta().getAPIVersion()).equals(stripZeros(Nms.minecraftVersion()));
    }

    /**
     * Plugin load, before any world exists: custom blocks are registered here (vanilla's block list only takes them
     * before the worlds load). PROTOTYPE: one built-in test block, lullaby:test_block (saved as stone, looks like a
     * diamond block); later register_block{ } in data scripts.
     */
    @Override
    public void onLoad() {
        if (!versionOk()) return; // onEnable says why
        try {
            io.github.kupfermeister.lullaby.nms.CustomBlocks.register("lullaby:test_block",
                    net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(),
                    net.minecraft.world.level.block.Blocks.DIAMOND_BLOCK.defaultBlockState());
        } catch (RuntimeException e) {
            getSLF4JLogger().error("Lullaby could not register its custom blocks; there are none this run", e); // FORMAT
        }
    }

    /**
     * Lullaby turns on before the worlds load (load: STARTUP in paper-plugin.yml), so each world gets Lullaby's chunk
     * codec for custom blocks before it reads a chunk. Everything else (scripts, block engine, LullaBlend) starts
     * once the server has loaded all worlds ({@link #startAfterWorlds}), as it did before.
     */
    @Override
    public void onEnable() {
        // Version lock: Lullaby uses Minecraft's own code (NMS), which changes between versions,
        // so it only runs on the exact Minecraft version it was built for. `api-version` in paper-plugin.yml is
        // filled from `minecraftVersion` in gradle.properties at build time.
        // Paper writes "26.3" as "26.3.0", Minecraft as "26.3": trailing ".0" parts are dropped before comparing.
        String builtFor = stripZeros(getPluginMeta().getAPIVersion());
        String running = stripZeros(Nms.minecraftVersion());
        if (!versionOk()) {
            // FORMAT: version lock error.
            // Console only, so no ⚠ (consoles often can't show it, see ErrorReporter). Paper's component logger
            // colors it in the console; logs/latest.log stays plain.
            getComponentLogger().error(Component.text("Lullaby " + getPluginMeta().getVersion()
                    + " needs Minecraft " + builtFor + ", but this server runs " + running
                    + ". Lullaby stays off. Use the Lullaby build for " + running + ".", NamedTextColor.RED));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        io.github.kupfermeister.lullaby.nms.CustomBlocks.start(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(LullabyCommand.build(this), "Lullaby: reload the scripts, show the version or the saved data", List.of());
            event.registrar().register(LullabyCommand.buildShort(this), "Lullaby: reload the scripts", List.of());
        });
        if (!getServer().getWorlds().isEmpty()) {
            startAfterWorlds(); // turned on later than the server start (worlds exist already)
            return;
        }
        getServer().getPluginManager().registerEvent(org.bukkit.event.server.ServerLoadEvent.class, new org.bukkit.event.Listener() { },
                org.bukkit.event.EventPriority.LOWEST, (listener, e) -> {
                    if (engine == null) startAfterWorlds();
                }, this);
    }

    private void startAfterWorlds() {
        io.github.kupfermeister.lullaby.nms.CustomBlocks.serverLoaded();
        // The block engine and LullaBlend live as long as the plugin (a reload doesn't touch them); started before the
        // scripts load, because the config (read by the library) sets their limits and LullaBlend's port.
        BlockEngine.start(this);
        LullaBlend.start(this);
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void join(org.bukkit.event.player.PlayerJoinEvent e) {
                BlockEngine.joined(e.getPlayer().getUniqueId());
            }
        }, this);

        engine = new Engine(this);
        try {
            engine.createDefaults();
        } catch (IOException e) {
            getSLF4JLogger().error("Lullaby could not create its files in {}", getDataPath(), e); // FORMAT
        }
        Engine.LoadResult started = engine.load();
        // The load event at start: plugins turn on before the server is ready, so it waits for the first tick (the
        // scheduler runs it then, as the console says "Done"; Paper drops it if the plugin stops before that tick).
        getServer().getScheduler().runTask(this, () -> engine.afterLoad(started, "start", null));

        tickTarget = engine;
        blocksOn = true;
        if (!tickRegistered) {
            tickRegistered = true;
            Nms.addTickable(() -> {
                if (blocksOn) {
                    LullaBlend.tick();
                    BlockEngine.tick();
                }
                Engine target = tickTarget;
                if (target != null) target.tick();
            });
        }
    }

    @Override
    public void onDisable() {
        tickTarget = null;
        blocksOn = false;
        LullaBlend.stop();
        BlockEngine.stop();
        io.github.kupfermeister.lullaby.nms.BreakSpeed.stop();
        if (engine != null) {
            engine.unload(false); // finals of running tasks run here, then server.data, w.data and c.data are saved
            engine.closeData();
            engine.worlds().close();
            engine.watchdog().shutdown();
        }
    }

    private static String stripZeros(String version) {
        while (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
        return version;
    }
}
