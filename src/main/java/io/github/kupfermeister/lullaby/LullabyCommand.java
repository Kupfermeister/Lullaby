package io.github.kupfermeister.lullaby;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * The one built-in command, {@code /lullaby} (Kupfermeister: everything necessary inside, nothing else built in):
 * {@code reload} and {@code version}. Registered as a Brigadier command like LightForce. It needs the permission
 * {@code lullaby.command}, which ops have by default (paper-plugin.yml). Reload is in Java so it works even when
 * every script is broken.
 */
final class LullabyCommand {

    private LullabyCommand() {
    }

    static LiteralCommandNode<CommandSourceStack> build(Lullaby plugin) {
        return Commands.literal("lullaby")
                .requires(source -> source.getSender().hasPermission("lullaby.command"))
                .then(Commands.literal("reload").executes(context -> {
                    Engine.LoadResult result = plugin.engine().load();
                    // FORMAT: reload summary
                    String text = "Reloaded " + result.scripts() + (result.scripts() == 1 ? " script" : " scripts")
                            + " in " + result.millis() + " ms";
                    if (result.errors() > 0) {
                        text += " with " + result.errors() + (result.errors() == 1 ? " error" : " errors");
                    }
                    context.getSource().getSender().sendMessage(Component.text(text,
                            result.errors() > 0 ? NamedTextColor.RED : NamedTextColor.GREEN));
                    // summary first, then every player's new commands, then scripts' reload handlers
                    plugin.engine().afterReload(result,
                            context.getSource().getExecutor() instanceof Player player ? player.getUniqueId() : null);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("version").executes(context -> {
                    // FORMAT: version line
                    context.getSource().getSender().sendMessage(Component.text("Lullaby "
                            + plugin.getPluginMeta().getVersion() + " for Minecraft "
                            + plugin.getPluginMeta().getAPIVersion().replaceAll("(\\.0)+$", "")));
                    return Command.SINGLE_SUCCESS;
                }))
                .build();
    }
}
