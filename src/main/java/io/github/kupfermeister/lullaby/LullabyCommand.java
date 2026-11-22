package io.github.kupfermeister.lullaby;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The built-in commands (Kupfermeister: everything necessary inside, nothing else built in): {@code /lullaby} with
 * {@code reload}, {@code version} and {@code data server [path]}, and {@code /lul}, which does exactly what
 * {@code /lullaby reload} does. Registered as a Brigadier command like LightForce. It needs the permission
 * {@code lullaby.command}, which ops have by default (paper-plugin.yml); {@code data} also needs {@code lullaby.data}.
 * Reload is in Java so it works even when every script is broken.
 *
 * <p>Tab completion of {@code data server} can be asked for off the main thread; the keys live in Lua, so the
 * suggestions are then worked out on the main thread and handed back later (Brigadier waits for them).
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
                .then(Commands.literal("data")
                        .requires(source -> source.getSender().hasPermission("lullaby.data"))
                        .then(Commands.literal("server")
                                .executes(context -> showData(plugin, context.getSource(), ""))
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> suggestKeys(plugin, builder))
                                        .executes(context -> showData(plugin, context.getSource(),
                                                StringArgumentType.getString(context, "path"))))))
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

    /** server.data (or one place in it, "stats.joins") as it is in memory now, colored like /data get. */
    private static int showData(Lullaby plugin, CommandSourceStack source, String path) {
        Object[] view;
        try {
            view = plugin.engine().viewData(path.strip());
        } catch (RuntimeException e) {
            // FORMAT: /lullaby data failed
            source.getSender().sendMessage(Component.text("Can't show server.data: " + e.getMessage(), NamedTextColor.RED));
            return 0;
        }
        String name = (String) view[1];
        if (view[0] == null) {
            // FORMAT: nothing at that place
            source.getSender().sendMessage(Component.text(name + " has nothing", NamedTextColor.RED));
            return 0;
        }
        // FORMAT: /lullaby data result: the place's name, then the value
        source.getSender().sendMessage(Component.text(name + ": ", NamedTextColor.GRAY).append((Component) view[0]));
        return Command.SINGLE_SUCCESS;
    }

    /** The keys one level below what was typed: "sta" -> "stats", "stats." -> "stats.joins", "stats.kills". */
    private static CompletableFuture<Suggestions> suggestKeys(Lullaby plugin, SuggestionsBuilder builder) {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(keys(plugin, builder));
        CompletableFuture<Suggestions> later = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> later.complete(keys(plugin, builder)));
        return later;
    }

    private static Suggestions keys(Lullaby plugin, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int dot = typed.lastIndexOf('.');
        String parent = dot < 0 ? "" : typed.substring(0, dot);
        List<String> keys = plugin.engine().dataKeys(parent);
        String lower = typed.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            String full = parent.isEmpty() ? key : parent + "." + key;
            if (full.toLowerCase(Locale.ROOT).startsWith(lower)) builder.suggest(full);
        }
        return builder.build();
    }
}
