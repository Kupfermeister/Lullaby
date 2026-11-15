package io.github.kupfermeister.lullaby.nms;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.spigotmc.SpigotConfig;

/**
 * Script commands ({@code command{ ... }}, library file commands.lua) as nodes on vanilla's own Brigadier dispatcher.
 * One instance per load of the scripts: {@link #clear} removes every command it added, and the next load adds the
 * new ones.
 *
 * <p>Threads, important: Paper builds each player's command tree on its own thread pool
 * ({@code Commands.COMMAND_SENDING_POOL}) and tab completion can run off the main thread, so the {@code requires}
 * predicates and suggestion providers here never call Lua. Instead, whenever a player's tree is refreshed (main
 * thread), Lua works out once what that player may see and which suggestions they get, and Java keeps that as an
 * immutable {@link Snapshot} in a concurrent map; predicates and providers only read snapshots. Building,
 * {@link #clear}, {@link #refresh} and running commands happen on the main thread.
 *
 * <p>Node ids: each node gets a number (its index in {@link #nodes}). Lua keeps its functions under the same numbers.
 * A "step" is the command itself or one of its branches; only a step's first node (its head) can be hidden, every
 * node after it is reachable only through the head anyway.
 */
public final class ScriptCommands {

    /** Hands a typed command to Lua. Main thread. */
    public interface Runner {
        /** {@code player} is null for the console or a command block; {@code args}: name, kind, value triples. */
        void run(int node, UUID player, List<Object> args);
    }

    /** What one player sees: the visible step heads and the suggestion lists worked out for them. Immutable. */
    private record Snapshot(Set<Integer> visible, Map<Integer, List<String>> suggestions) {
    }

    private record Node(int parent, boolean argument, String name, String type,
                        CommandNode<CommandSourceStack> brigadier) {
    }

    private final Runner runner;
    private final List<Node> nodes = new ArrayList<>();
    private final List<CommandNode<CommandSourceStack>> rootNodes = new ArrayList<>(); // what this load added
    private CommandSourceStack running; // who typed the command being run right now (for reject), else null
    private final Map<UUID, Snapshot> snapshots = new ConcurrentHashMap<>();
    // Main thread only (see treeEvent): step heads by node (aliases too), players with a correct build on its way.
    private final Map<CommandNode<CommandSourceStack>, Integer> headOf = new IdentityHashMap<>();
    private final Set<UUID> buildPending = new HashSet<>();

    public ScriptCommands(Runner runner) {
        this.runner = runner;
    }

    private static RootCommandNode<CommandSourceStack> root() {
        return MinecraftServer.getServer().getCommands().getDispatcher().getRoot();
    }

    /** True if a command of this name exists already (vanilla, another plugin or a script). */
    public static boolean exists(String name) {
        return root().getChild(name) != null;
    }

    /**
     * Makes one node and hangs it below {@code parent} (-1: a new command, put into the game later by
     * {@link #register}). {@code argument}: a value (type word, text, integer, number, bool or player) instead of a
     * word. {@code head}: the first node of a step, hidden per player (and by {@code permission} for the console).
     * {@code suggests}: the script gave suggestions (a fixed list here, or per player in the snapshots). Returns the id.
     */
    public int addNode(int parent, boolean argument, String name, String type, double min, double max,
                       boolean suggests, List<String> fixedSuggestions, boolean executable, boolean head,
                       String permission) {
        int id = nodes.size();
        ArgumentBuilder<CommandSourceStack, ?> builder;
        if (argument) {
            RequiredArgumentBuilder<CommandSourceStack, ?> value =
                    RequiredArgumentBuilder.argument(name, argumentType(type, min, max));
            if (suggests) value.suggests(suggestions(id, List.copyOf(fixedSuggestions)));
            builder = value;
        } else {
            builder = LiteralArgumentBuilder.literal(name);
        }
        if (head) builder.requires(visible(id, permission));
        if (executable) builder.executes(executor(id));
        CommandNode<CommandSourceStack> node = builder.build();
        nodes.add(new Node(parent, argument, name, type, node));
        if (head) headOf.put(node, id);
        if (parent >= 0) nodes.get(parent).brigadier().addChild(node);
        return id;
    }

    /**
     * Another word for a word step (an alias): a copy of the node with the same visibility and function that shares
     * its children, hung next to the original (for the command itself: straight into the game). Called once the
     * step's children are all built.
     */
    public void addAlias(int node, String alias) {
        Node original = nodes.get(node);
        CommandNode<CommandSourceStack> source = original.brigadier();
        LiteralArgumentBuilder<CommandSourceStack> copy = LiteralArgumentBuilder.<CommandSourceStack>literal(alias)
                .requires(source.getRequirement());
        if (source.getCommand() != null) copy.executes(source.getCommand());
        CommandNode<CommandSourceStack> built = copy.build();
        headOf.put(built, headOf.get(source));
        for (CommandNode<CommandSourceStack> child : source.getChildren()) built.addChild(child);
        if (original.parent() >= 0) {
            nodes.get(original.parent()).brigadier().addChild(built);
        } else {
            root().addChild(built);
            rootNodes.add(built);
        }
    }

    /** Puts a finished command into the game (its aliases go in through {@link #addAlias}). */
    public void register(int command) {
        CommandNode<CommandSourceStack> node = nodes.get(command).brigadier();
        root().addChild(node);
        rootNodes.add(node);
    }

    /**
     * Puts the commands onto a new dispatcher after a data pack reload replaced the old one. A name someone else
     * took in the meantime is left to them (adding ours would merge the two trees).
     */
    public void reattach() {
        RootCommandNode<CommandSourceStack> root = root();
        for (CommandNode<CommandSourceStack> node : rootNodes) {
            if (root.getChild(node.getName()) == null) root.addChild(node);
        }
    }

    /** Removes every command this load added (only its own nodes) and forgets all snapshots. */
    public void clear() {
        RootCommandNode<CommandSourceStack> root = root();
        for (CommandNode<CommandSourceStack> node : rootNodes) {
            if (root.getChild(node.getName()) == node) root.removeCommand(node.getName());
        }
        rootNodes.clear();
        nodes.clear();
        headOf.clear();
        buildPending.clear();
        snapshots.clear();
    }

    public boolean isEmpty() {
        return rootNodes.isEmpty();
    }

    /**
     * A command's check rejected a value: tells whoever typed it the way Minecraft shows its own command errors
     * (plain red, no ⚠: a rejection is not a script error). Only works while the command is being
     * run; checks run before the function's first wait, so they always are.
     */
    public void reject(String text) {
        if (running == null) return;
        // FORMAT: check rejection (the script's own text, literal as /tellraw "text", in red)
        running.sendFailure(Component.literal(text).withStyle(ChatFormatting.RED));
    }

    /** Stores what an online player sees and resends their command tree (vanilla's Commands packet). Main thread. */
    public void refresh(UUID uuid, Set<Integer> visible, Map<Integer, List<String>> suggestions) {
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
        if (player == null) return;
        snapshots.put(uuid, new Snapshot(Set.copyOf(visible), Map.copyOf(suggestions)));
        buildPending.add(uuid);
        MinecraftServer.getServer().getCommands().sendCommands(player);
    }

    /**
     * Fixes a tree-order race ("resend when stale"). Several trees for one player can be built at the
     * same time on Paper's 2-thread pool (vanilla's own at join before Lullaby knows what the player sees, Lullaby's
     * at join, refresh_commands twice, a data pack reload), so an outdated one could reach the player last. Paper
     * fires AsyncPlayerSendCommandsEvent on the main thread right before each tree is sent ({@code sync} true); there
     * this checks the tree itself: does it hold exactly the script command parts this player may see now? If not,
     * and no newer build for this player is on its way, it builds once more; that build starts after this tree is
     * sent, so its tree arrives later. At most one extra build per mismatch, so a mismatch that can't be fixed (e.g.
     * another plugin removes our nodes) never loops. Main thread only; never calls Lua.
     */
    public void treeEvent(UUID uuid, CommandNode<?> tree, boolean sync) {
        // While the server stops, Paper may run the "main-thread" firing on a pool thread (the main thread no longer
        // takes tasks); then this skips it, since the main thread may be clearing these lists at the same moment.
        if (!sync || !Bukkit.isPrimaryThread()) return;
        boolean pending = buildPending.remove(uuid);
        if (matches(uuid, tree) || pending) return;
        ServerPlayer player = MinecraftServer.getServer().getPlayerList().getPlayer(uuid);
        if (player == null) return;
        buildPending.add(uuid);
        MinecraftServer.getServer().getCommands().sendCommands(player);
    }

    /**
     * After /minecraft:reload: vanilla already sent every player a tree without the script commands (its build is
     * still on its way); marking all players pending lets that one pass, so only this resend's tree is checked.
     */
    public void resendAll() {
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            buildPending.add(player.getUUID());
            MinecraftServer.getServer().getCommands().sendCommands(player);
        }
    }

    /** Whether a sent tree shows each of our nodes exactly when the player's snapshot says so. */
    private boolean matches(UUID uuid, CommandNode<?> tree) {
        Snapshot snapshot = snapshots.get(uuid);
        RootCommandNode<CommandSourceStack> root = root();
        for (CommandNode<CommandSourceStack> node : rootNodes) {
            if (root.getChild(node.getName()) != node) continue; // not in the game (name taken by someone else)
            if (!matches(snapshot, node, tree.getChild(node.getName()))) return false;
        }
        return true;
    }

    private boolean matches(Snapshot snapshot, CommandNode<CommandSourceStack> ours, CommandNode<?> sent) {
        // spigot.yml send-namespaced: false leaves out every node whose name has a ':' (at any depth), so such a node
        // is never in a sent tree and must not count as missing.
        if (!SpigotConfig.sendNamespaced && ours.getName().contains(":")) return true;
        Integer head = headOf.get(ours);
        boolean expected = head == null || snapshot != null && snapshot.visible().contains(head);
        if (expected != (sent != null)) return false;
        if (sent == null) return true;
        for (CommandNode<CommandSourceStack> child : ours.getChildren()) {
            if (!matches(snapshot, child, sent.getChild(child.getName()))) return false;
        }
        return true;
    }

    public void forget(UUID uuid) {
        snapshots.remove(uuid);
    }

    // ---- what Brigadier calls: requires and suggestions on any thread, execution on the main thread ----

    private static ArgumentType<?> argumentType(String type, double min, double max) {
        // Lua sends -huge/huge for "no limit"; Brigadier's own "no limit" is the largest finite value.
        if (Double.isInfinite(min)) min = -Double.MAX_VALUE;
        if (Double.isInfinite(max)) max = Double.MAX_VALUE;
        return switch (type) {
            case "word" -> StringArgumentType.word();
            case "text" -> StringArgumentType.greedyString();
            case "integer" -> IntegerArgumentType.integer((int) Math.max(min, Integer.MIN_VALUE),
                    (int) Math.min(max, Integer.MAX_VALUE));
            case "number" -> DoubleArgumentType.doubleArg(min, max);
            case "bool" -> BoolArgumentType.bool();
            case "player" -> EntityArgument.player();
            default -> throw new IllegalArgumentException("unknown argument type " + type);
        };
    }

    /** Players see what their snapshot shows (nothing before their first refresh); others need the permission. */
    private Predicate<CommandSourceStack> visible(int id, String permission) {
        return source -> {
            if (source.getEntity() instanceof ServerPlayer player) {
                Snapshot snapshot = snapshots.get(player.getUUID());
                return snapshot != null && snapshot.visible().contains(id);
            }
            return permission.isEmpty() || source.getBukkitSender().hasPermission(permission);
        };
    }

    /** A player's own list from their snapshot if Lua made one (suggest = function), else the fixed list. */
    private SuggestionProvider<CommandSourceStack> suggestions(int id, List<String> fixed) {
        return (context, builder) -> {
            List<String> list = fixed;
            if (context.getSource().getEntity() instanceof ServerPlayer player) {
                Snapshot snapshot = snapshots.get(player.getUUID());
                if (snapshot != null) list = snapshot.suggestions().getOrDefault(id, fixed);
            }
            return suggest(builder, list);
        };
    }

    private static CompletableFuture<Suggestions> suggest(SuggestionsBuilder builder, List<String> list) {
        String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String option : list) {
            if (option.toLowerCase(Locale.ROOT).startsWith(typed)) builder.suggest(option);
        }
        return builder.buildFuture();
    }

    /** Collects the values along the node's path (left out optional ones are missing) and hands them to Lua. */
    private Command<CommandSourceStack> executor(int id) {
        return context -> {
            List<Object> args = new ArrayList<>();
            for (int at = id; at >= 0; at = nodes.get(at).parent()) {
                Node node = nodes.get(at);
                if (node.argument()) addValue(context, node, args);
            }
            UUID player = context.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
            CommandSourceStack outer = running; // a command may run another one
            running = context.getSource();
            try {
                runner.run(id, player, args);
            } finally {
                running = outer;
            }
            return Command.SINGLE_SUCCESS;
        };
    }

    private static void addValue(CommandContext<CommandSourceStack> context, Node node, List<Object> args)
            throws CommandSyntaxException {
        if (!typed(context, node.name())) return;
        String kind = "v";
        Object value = switch (node.type()) {
            case "integer" -> (long) context.getArgument(node.name(), Integer.class);
            case "number" -> context.getArgument(node.name(), Double.class);
            case "bool" -> context.getArgument(node.name(), Boolean.class);
            case "player" -> EntityArgument.getPlayer(context, node.name()).getUUID().toString();
            default -> context.getArgument(node.name(), String.class);
        };
        if (node.type().equals("player")) kind = "player";
        args.add(node.name());
        args.add(kind);
        args.add(value);
    }

    /** Whether the player typed this value (Brigadier only knows typed ones; an optional one may be left out). */
    private static boolean typed(CommandContext<CommandSourceStack> context, String name) {
        for (var parsed : context.getNodes()) {
            if (parsed.getNode().getName().equals(name) && !(parsed.getNode() instanceof LiteralCommandNode)) return true;
        }
        return false;
    }
}
