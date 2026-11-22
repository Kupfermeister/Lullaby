package io.github.kupfermeister.lullaby;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * The built-in commands (Kupfermeister: everything necessary inside, nothing else built in): {@code /lullaby} with
 * {@code reload} and {@code version}, and {@code /lul}, which does exactly what {@code /lullaby reload} does. Registered as a Brigadier command like LightForce. It needs the permission
 * {@code lullaby.command}, which ops have by default (paper-plugin.yml). Reload is in Java so it works even when
 * every script is broken.
 */
final class LullabyCommand {

    private LullabyCommand() {
    }

    static LiteralCommandNode<CommandSourceStack> build(Lullaby plugin) {
        return Commands.literal("lullaby")
                .requires(source -> source.getSender().hasPermission("lullaby.command"))
                .then(Commands.literal("reload").executes(context -> reload(plugin, context.getSource())))
                .then(Commands.literal("version").executes(context -> {
                    // FORMAT: version line
                    context.getSource().getSender().sendMessage(Component.text("Lullaby "
                            + plugin.getPluginMeta().getVersion() + " for Minecraft "
                            + plugin.getPluginMeta().getAPIVersion().replaceAll("(\\.0)+$", "")));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }

    /** {@code /lul}: exactly {@code /lullaby reload}, short for typing it often. Same permission. */
    static LiteralCommandNode<CommandSourceStack> buildShort(Lullaby plugin) {
        return Commands.literal("lul")
                .requires(source -> source.getSender().hasPermission("lullaby.command"))
                .executes(context -> reload(plugin, context.getSource()))
                .build();
    }

    private static int reload(Lullaby plugin, CommandSourceStack source) {
        Engine.LoadResult result = plugin.engine().load();
        // FORMAT: reload summary
        String text = "Reloaded " + result.scripts() + (result.scripts() == 1 ? " script" : " scripts")
                + " in " + result.millis() + " ms";
        if (result.errors() > 0) {
            text += " with " + result.errors() + (result.errors() == 1 ? " error" : " errors");
        }
        source.getSender().sendMessage(Component.text(text,
                result.errors() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
        // summary first, then every player's new commands, then scripts' reload handlers
        plugin.engine().afterReload(result, source.getExecutor() instanceof Player player ? player.getUniqueId() : null);
        return Command.SINGLE_SUCCESS;
    }
}
