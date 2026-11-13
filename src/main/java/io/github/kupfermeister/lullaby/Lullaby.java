package io.github.kupfermeister.lullaby;

import io.github.kupfermeister.lullaby.nms.Nms;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entry point. Paper creates this class (named in paper-plugin.yml) and calls onEnable on the main thread.
 * Without scripts Lullaby must change nothing in the game, so this stays empty until scripts exist.
 */
public final class Lullaby extends JavaPlugin {

    @Override
    public void onEnable() {
        // Version lock (Kupfermeister 2026-11-13): Lullaby uses Minecraft's own code (NMS), which changes between versions,
        // so it only runs on the exact Minecraft version it was built for. `api-version` in paper-plugin.yml is
        // filled from `minecraftVersion` in gradle.properties at build time.
        // Paper writes "26.3" as "26.3.0", Minecraft as "26.3": trailing ".0" parts are dropped before comparing.
        String builtFor = stripZeros(getPluginMeta().getAPIVersion());
        String running = stripZeros(Nms.minecraftVersion());
        if (!builtFor.equals(running)) {
            // FORMAT: version lock error.
            // Error style (Kupfermeister): vanilla red or gold, starting with ⚠. The console has no vanilla way to show
            // colors, so this uses Paper's component logger: colored in the console, plain in logs/latest.log.
            getComponentLogger().error(Component.text("⚠ Lullaby " + getPluginMeta().getVersion()
                    + " needs Minecraft " + builtFor + ", but this server runs " + running
                    + ". Lullaby stays off. Use the Lullaby build for " + running + ".", NamedTextColor.RED));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private static String stripZeros(String version) {
        while (version.endsWith(".0")) version = version.substring(0, version.length() - 2);
        return version;
    }
}
