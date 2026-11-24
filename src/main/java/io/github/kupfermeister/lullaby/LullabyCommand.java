package io.github.kupfermeister.lullaby;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * The built-in commands (Kupfermeister: everything necessary inside, nothing else built in): {@code /lullaby} with
 * {@code reload}, {@code version}, {@code data server|world <world>|chunk [path]} (chunk: the one you stand in) and
 * {@code run <code>}, and {@code /lul}, which does exactly what
 * {@code /lullaby reload} does. Registered as a Brigadier command like LightForce. It needs the permission
 * {@code lullaby.command}, which ops have by default (paper-plugin.yml); {@code data} also needs {@code lullaby.data},
 * {@code run} also needs {@code lullaby.run}.
 * Reload is in Java so it works even when every script is broken.
 *
 * <p>Tab completion of {@code data} paths can be asked for off the main thread; the keys live in Lua, so the
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
                                .executes(context -> showData(plugin, context.getSource(), "server", ""))
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> suggestKeys(plugin, "server", builder))
                                        .executes(context -> showData(plugin, context.getSource(), "server",
                                                StringArgumentType.getString(context, "path")))))
                        .then(Commands.literal("world")
                                .then(Commands.argument("world", ArgumentTypes.world())
                                        .executes(context -> showData(plugin, context.getSource(),
                                                world(context.getArgument("world", World.class)), ""))
                                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                                .suggests((context, builder) -> suggestKeys(plugin,
                                                        world(context.getArgument("world", World.class)), builder))
                                                .executes(context -> showData(plugin, context.getSource(),
                                                        world(context.getArgument("world", World.class)),
                                                        StringArgumentType.getString(context, "path"))))))
                        .then(Commands.literal("chunk")
                                .executes(context -> showChunk(plugin, context.getSource(), ""))
                                .then(Commands.argument("path", StringArgumentType.greedyString())
                                        .suggests((context, builder) -> {
                                            String which = chunk(context.getSource());
                                            return which == null ? builder.buildFuture() : suggestKeys(plugin, which, builder);
                                        })
                                        .executes(context -> showChunk(plugin, context.getSource(),
                                                StringArgumentType.getString(context, "path"))))))
                .then(Commands.literal("run")
                        .requires(source -> source.getSender().hasPermission("lullaby.run"))
                        .then(Commands.argument("code", StringArgumentType.greedyString())
                                .executes(context -> run(plugin, context.getSource(),
                                        StringArgumentType.getString(context, "code")))))
                .build();
    }

    /**
     * {@code /lullaby run <code>}: one line of Lua, started at once as a task with {@code me} as the player who typed
     * it (or the one {@code /execute as} names; nil from the console); the sender gets "Done" when it ends without an
     * error. Every use is written to the console. A command block (or any sender that is neither a player nor the
     * console) is refused unless the config's unlock.command_blocks is true; Lua checks that (raw_run in loader.lua)
     * so the refusal is a normal error.
     */
    private static int run(Lullaby plugin, CommandSourceStack source, String code) {
        Player sender = source.getSender() instanceof Player p ? p : null;
        Player me = source.getExecutor() instanceof Player p ? p : sender;
        boolean fromBlock = sender == null
                && !(source.getSender() instanceof org.bukkit.command.ConsoleCommandSender);
        String who = source.getSender().getName();
        // FORMAT: console line for every /lullaby run
        plugin.getSLF4JLogger().info("{} used /lullaby run: {}", who, code);
        plugin.engine().runRaw(code, me == null ? null : me.getUniqueId(),
                sender == null ? null : sender.getUniqueId(), fromBlock, who);
        return Command.SINGLE_SUCCESS;
    }

    /** "world minecraft:arena_7" for Engine.viewData. */
    private static String world(World world) {
        return "world " + Nms.worldIdOf(world);
    }

    /** "chunk minecraft:overworld 3 -5": the chunk the command's player (or command block, ...) is in; null for the console. */
    private static String chunk(CommandSourceStack source) {
        if (source.getExecutor() == null && !(source.getSender() instanceof Player)) return null;
        Location at = source.getLocation();
        return "chunk " + Nms.worldIdOf(at.getWorld()) + " " + (at.getBlockX() >> 4) + " " + (at.getBlockZ() >> 4);
    }

    private static int showChunk(Lullaby plugin, CommandSourceStack source, String path) {
        String which = chunk(source);
        if (which == null) {
            // FORMAT: /lullaby data chunk from the console
            source.getSender().sendMessage(Component.text("/lullaby data chunk shows the chunk you stand in: use it in"
                    + " the game", NamedTextColor.RED));
            return 0;
        }
        return showData(plugin, source, which, path);
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
        // summary first, then every player's new commands, then scripts' load handlers
        plugin.engine().afterLoad(result, "reload", source.getExecutor() instanceof Player player ? player.getUniqueId() : null);
        return Command.SINGLE_SUCCESS;
    }

    /** Saved data (or one place in it, "stats.joins") as it is in memory now, colored like /data get. */
    private static int showData(Lullaby plugin, CommandSourceStack source, String which, String path) {
        Object[] view;
        try {
            view = plugin.engine().viewData(which, path.strip());
        } catch (RuntimeException e) {
            // FORMAT: /lullaby data failed
            source.getSender().sendMessage(Component.text("Can't show the data: " + e.getMessage(), NamedTextColor.RED));
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
    private static CompletableFuture<Suggestions> suggestKeys(Lullaby plugin, String which, SuggestionsBuilder builder) {
        if (Bukkit.isPrimaryThread()) return CompletableFuture.completedFuture(keys(plugin, which, builder));
        CompletableFuture<Suggestions> later = new CompletableFuture<>();
        Bukkit.getScheduler().runTask(plugin, () -> later.complete(keys(plugin, which, builder)));
        return later;
    }

    private static Suggestions keys(Lullaby plugin, String which, SuggestionsBuilder builder) {
        String typed = builder.getRemaining();
        int dot = typed.lastIndexOf('.');
        String parent = dot < 0 ? "" : typed.substring(0, dot);
        List<String> keys = plugin.engine().dataKeys(which, parent);
        String lower = typed.toLowerCase(Locale.ROOT);
        for (String key : keys) {
            String full = parent.isEmpty() ? key : parent + "." + key;
            if (full.toLowerCase(Locale.ROOT).startsWith(lower)) builder.suggest(full);
        }
        return builder.build();
    }
}
