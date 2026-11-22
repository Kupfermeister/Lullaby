package io.github.kupfermeister.lullaby;

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

    private Engine engine;

    Engine engine() {
        return engine;
    }

    @Override
    public void onEnable() {
        // Version lock: Lullaby uses Minecraft's own code (NMS), which changes between versions,
        // so it only runs on the exact Minecraft version it was built for. `api-version` in paper-plugin.yml is
        // filled from `minecraftVersion` in gradle.properties at build time.
        // Paper writes "26.3" as "26.3.0", Minecraft as "26.3": trailing ".0" parts are dropped before comparing.
        String builtFor = stripZeros(getPluginMeta().getAPIVersion());
        String running = stripZeros(Nms.minecraftVersion());
        if (!builtFor.equals(running)) {
            // FORMAT: version lock error.
            // Console only, so no ⚠ (consoles often can't show it, see ErrorReporter). Paper's component logger
            // colors it in the console; logs/latest.log stays plain.
            getComponentLogger().error(Component.text("Lullaby " + getPluginMeta().getVersion()
                    + " needs Minecraft " + builtFor + ", but this server runs " + running
                    + ". Lullaby stays off. Use the Lullaby build for " + running + ".", NamedTextColor.RED));
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        engine = new Engine(this);
        try {
            engine.createDefaults();
        } catch (IOException e) {
            getSLF4JLogger().error("Lullaby could not create its files in {}", getDataPath(), e); // FORMAT
        }
        engine.load();

        tickTarget = engine;
        if (!tickRegistered) {
            tickRegistered = true;
            Nms.addTickable(() -> {
                Engine target = tickTarget;
                if (target != null) target.tick();
            });
        }

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register(LullabyCommand.build(this), "Lullaby: reload the scripts, show the version or the saved data", List.of());
            event.registrar().register(LullabyCommand.buildShort(this), "Lullaby: reload the scripts", List.of());
        });
    }

    @Override
    public void onDisable() {
        tickTarget = null;
        if (engine != null) {
            engine.unload(false); // finals of running tasks run here, then server.data is saved
            engine.data().close();
            engine.worlds().close();
            engine.watchdog().shutdown();
        }
    }

    private static String stripZeros(String version) {
        while (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
        return version;
    }
}
