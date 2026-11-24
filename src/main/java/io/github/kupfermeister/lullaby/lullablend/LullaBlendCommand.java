package io.github.kupfermeister.lullaby.lullablend;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code /lullablend}: the game side of LullaBlend (permission {@code lullaby.lullablend}, ops by default).
 * <pre>
 *   token                      a new token for Blender (click to copy); the old one stops working
 *   origin                     Blender's origin = the block you stand in, Blender's north = the way you look
 *   origin x y z [facing] [world]
 *   mode mesh|box|chunks       what happens around the build: nothing / its box emptied / its chunks emptied
 *   show | hide                the preview (fake blocks) on or off
 *   viewers                    who sees the preview; viewers add|remove &lt;player&gt;
 *   place                      write the preview into the world for real
 *   undo                       take the last place back
 *   status
 *   as &lt;player&gt; ...            the same for another player's session (console, admins)
 * </pre>
 */
public final class LullaBlendCommand {

    private LullaBlendCommand() {
    }

    private static final String[] FACINGS = {"north", "east", "south", "west"};

    public static LiteralCommandNode<CommandSourceStack> build() {
        var root = Commands.literal("lullablend")
                .requires(source -> source.getSender().hasPermission("lullaby.lullablend"));
        subcommands(root, ctx -> self(ctx.getSource().getSender()));
        root.then(Commands.literal("as").then(subcommands(Commands.argument("player", StringArgumentType.word())
                .suggests((ctx, b) -> {
                    for (Player p : Bukkit.getOnlinePlayers()) b.suggest(p.getName());
                    return b.buildFuture();
                }), ctx -> other(StringArgumentType.getString(ctx, "player")))));
        return root.build();
    }

    private static UUID self(CommandSender sender) {
        if (sender instanceof Player p) return p.getUniqueId();
        // FORMAT: console without "as"
        sender.sendMessage(Component.text("From the console, use /lullablend as <player> ...", NamedTextColor.RED));
        return null;
    }

    @SuppressWarnings("deprecation") // getOfflinePlayer(name): the only way to name an offline player
    private static UUID other(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer o = Bukkit.getOfflinePlayer(name);
        return o.getUniqueId();
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T subcommands(
            T node, Function<CommandContext<CommandSourceStack>, UUID> who) {
        node.then(Commands.literal("token").executes(ctx -> run(ctx, who, (s, l, sender) -> {
            String token = l.newToken(s.player);
            // FORMAT: the new token, click to copy
            sender.sendMessage(Component.text("LullaBlend token for " + LullaBlend.name(s.player) + ": ", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(token, NamedTextColor.WHITE)
                            .clickEvent(ClickEvent.copyToClipboard(token))
                            .hoverEvent(HoverEvent.showText(Component.text("click to copy, then paste it into Blender's LullaBlend panel")))));
        })));
        node.then(Commands.literal("origin")
                .executes(ctx -> run(ctx, who, (s, l, sender) -> {
                    Location at = ctx.getSource().getLocation();
                    l.setOrigin(s, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), facing(at.getYaw()));
                }))
                .then(Commands.argument("pos", ArgumentTypes.blockPosition())
                        .executes(ctx -> origin(ctx, who, null, null))
                        .then(Commands.argument("facing", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (String f : FACINGS) b.suggest(f);
                                    return b.buildFuture();
                                })
                                .executes(ctx -> origin(ctx, who, StringArgumentType.getString(ctx, "facing"), null))
                                .then(Commands.argument("world", ArgumentTypes.world())
                                        .executes(ctx -> origin(ctx, who, StringArgumentType.getString(ctx, "facing"),
                                                ctx.getArgument("world", World.class)))))));
        var mode = Commands.literal("mode");
        for (LullaBlend.Mode m : LullaBlend.Mode.values()) {
            mode.then(Commands.literal(m.name().toLowerCase(Locale.ROOT)).executes(ctx -> run(ctx, who, (s, l, sender) -> l.setMode(s, m))));
        }
        node.then(mode);
        node.then(Commands.literal("show").executes(ctx -> run(ctx, who, (s, l, sender) -> l.setShowing(s, true))));
        node.then(Commands.literal("hide").executes(ctx -> run(ctx, who, (s, l, sender) -> l.setShowing(s, false))));
        node.then(Commands.literal("place").executes(ctx -> run(ctx, who, (s, l, sender) -> l.place(s, text -> say(sender, text)))));
        node.then(Commands.literal("undo").executes(ctx -> run(ctx, who, (s, l, sender) -> l.undo(s, text -> say(sender, text)))));
        node.then(Commands.literal("status").executes(ctx -> run(ctx, who, (s, l, sender) -> {
            // FORMAT: /lullablend status
            say(sender, (s.link != null ? "Blender connected" : "Blender not connected")
                    + "; " + (s.hasOrigin ? "origin " + LullaBlend.originText(s) : "no origin yet")
                    + "; mode " + s.mode.name().toLowerCase(Locale.ROOT)
                    + "; preview " + (s.showing ? "on" : "off")
                    + (s.edit != null ? " (" + s.edit.blocks() + " blocks)" : "")
                    + "; viewers " + String.join(", ", s.viewers.stream().map(LullaBlend::name).toList())
                    + (s.lastProblem != null ? "; last problem: " + s.lastProblem : ""));
        })));
        node.then(Commands.literal("viewers")
                .executes(ctx -> run(ctx, who, (s, l, sender) ->
                        say(sender, "viewers: " + String.join(", ", s.viewers.stream().map(LullaBlend::name).toList())))) // FORMAT
                .then(Commands.literal("add").then(Commands.argument("viewer", StringArgumentType.word())
                        .suggests((ctx, b) -> {
                            for (Player p : Bukkit.getOnlinePlayers()) b.suggest(p.getName());
                            return b.buildFuture();
                        })
                        .executes(ctx -> run(ctx, who, (s, l, sender) -> {
                            Set<UUID> v = new LinkedHashSet<>(s.viewers);
                            v.add(other(StringArgumentType.getString(ctx, "viewer")));
                            l.setViewers(s, v);
                            say(sender, "viewers: " + String.join(", ", v.stream().map(LullaBlend::name).toList())); // FORMAT
                        }))))
                .then(Commands.literal("remove").then(Commands.argument("viewer", StringArgumentType.word())
                        .executes(ctx -> run(ctx, who, (s, l, sender) -> {
                            Set<UUID> v = new LinkedHashSet<>(s.viewers);
                            v.remove(other(StringArgumentType.getString(ctx, "viewer")));
                            l.setViewers(s, v);
                            say(sender, "viewers: " + String.join(", ", v.stream().map(LullaBlend::name).toList())); // FORMAT
                        })))));
        return node;
    }

    private interface Action {
        void run(LullaBlend.Session s, LullaBlend l, CommandSender sender) throws Exception;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Function<CommandContext<CommandSourceStack>, UUID> who, Action action) {
        CommandSender sender = ctx.getSource().getSender();
        LullaBlend l = LullaBlend.get();
        UUID id = who.apply(ctx);
        if (l == null || id == null) return 0;
        l.echo = sender;
        try {
            action.run(l.session(id), l, sender);
        } catch (Exception e) {
            say(sender, "failed: " + e.getMessage()); // FORMAT
            return 0;
        } finally {
            l.echo = null;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int origin(CommandContext<CommandSourceStack> ctx, Function<CommandContext<CommandSourceStack>, UUID> who,
                              String facing, World world) {
        return run(ctx, who, (s, l, sender) -> {
            var pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
            World w = world != null ? world : ctx.getSource().getLocation().getWorld();
            int turns = facing == null ? facing(ctx.getSource().getLocation().getYaw()) : java.util.Arrays.asList(FACINGS).indexOf(facing);
            if (turns < 0) throw new IllegalArgumentException("facing must be north, east, south or west, got " + facing);
            l.setOrigin(s, w, pos.blockX(), pos.blockY(), pos.blockZ(), turns);
        });
    }

    /** Minecraft's yaw (0 south, 90 west, 180 north, 270 east) to quarter turns from north, clockwise. */
    private static int facing(float yaw) {
        int q = Math.round(yaw / 90f) & 3; // 0 south, 1 west, 2 north, 3 east
        return new int[]{2, 3, 0, 1}[q];
    }

    private static void say(CommandSender sender, String text) {
        // FORMAT: LullaBlend command answer
        sender.sendMessage(Component.text("LullaBlend: ", NamedTextColor.LIGHT_PURPLE).append(Component.text(text, NamedTextColor.GRAY)));
    }
}
