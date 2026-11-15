package io.github.kupfermeister.lullaby.nms;

import io.papermc.paper.adventure.PaperAdventure;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.kyori.adventure.text.Component;

/**
 * Small vanilla reads and actions that do not belong to a bigger NMS class yet. Main thread only (vanilla's world and
 * player objects are not thread-safe).
 */
public final class Nms {

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private Nms() {
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    /** The Minecraft version the server runs, vanilla's own name for it, e.g. "26.3". */
    public static String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    /** Runs {@code tick} at the end of every server tick (vanilla's own list of per-tick jobs; it has no removal). */
    public static void addTickable(Runnable tick) {
        server().addTickable(tick);
    }

    // ---- players ----

    /** UUID texts of every online player. */
    public static List<String> onlinePlayers() {
        return server().getPlayerList().getPlayers().stream().map(player -> player.getUUID().toString()).toList();
    }

    /** Resends every online player's command tree (vanilla's Commands packet). */
    public static void resendCommands() {
        for (ServerPlayer player : server().getPlayerList().getPlayers()) server().getCommands().sendCommands(player);
    }

    public static boolean isOnline(UUID uuid) {
        return server().getPlayerList().getPlayer(uuid) != null;
    }

    /** The player's name: online, or from the server's name cache; null if unknown. */
    public static String playerName(UUID uuid) {
        ServerPlayer player = server().getPlayerList().getPlayer(uuid);
        if (player != null) return player.getScoreboardName();
        return server().services().nameToIdCache().get(uuid).map(NameAndId::name).orElse(null);
    }

    /**
     * A player from a name or UUID text: online, or one who has played here (has a save file). Never asks Mojang's
     * servers (that would block the main thread), so an offline name is found only if the server has seen it.
     */
    public static UUID findPlayer(String text) {
        if (UUID_TEXT.matcher(text).matches()) {
            UUID uuid = UUID.fromString(text);
            return isOnline(uuid) || hasSaveFile(uuid) ? uuid : null;
        }
        ServerPlayer player = server().getPlayerList().getPlayerByName(text);
        if (player != null) return player.getUUID();
        NameAndId cached = server().services().nameToIdCache().getIfCached(text);
        return cached != null && hasSaveFile(cached.id()) ? cached.id() : null;
    }

    private static boolean hasSaveFile(UUID uuid) {
        return Files.exists(server().getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat"));
    }

    /** Sends a chat message as vanilla's system message (System Chat Message packet); false if offline. */
    public static boolean sendMessage(UUID uuid, net.minecraft.network.chat.Component text) {
        ServerPlayer player = server().getPlayerList().getPlayer(uuid);
        if (player == null) return false;
        player.sendSystemMessage(text);
        return true;
    }

    /** Literal text (as /tellraw "text") as a system message; false if offline. */
    public static boolean sendLiteral(UUID uuid, String text) {
        return sendMessage(uuid, net.minecraft.network.chat.Component.literal(text));
    }

    /** Sends a system message to every online op. */
    public static void sendToOps(Component text) {
        MinecraftServer server = server();
        if (server == null) return;
        net.minecraft.network.chat.Component vanilla = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!server.getPlayerList().isOp(player.nameAndId())) continue;
            if (vanilla == null) vanilla = PaperAdventure.asVanilla(text);
            player.sendSystemMessage(vanilla);
        }
    }

    // ---- materials, worlds, blocks (vanilla ids; "stone" means "minecraft:stone") ----

    private static Identifier identifier(String text) {
        String id = text.toLowerCase(Locale.ROOT);
        return Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
    }

    /** A full id ("overworld" -> "minecraft:overworld") if the text is a valid id at all; null otherwise. */
    public static String dimensionId(String text) {
        Identifier id = identifier(text);
        return id == null ? null : id.toString();
    }

    private static ServerLevel level(String text) {
        Identifier id = identifier(text);
        return id == null ? null : server().getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    /** The full id of a loaded world ("overworld" -> "minecraft:overworld"); null if there is none. */
    public static String worldId(String text) {
        ServerLevel level = level(text);
        return level == null ? null : level.dimension().identifier().toString();
    }

    /** The key of a loaded world by its full id (the library checked it exists), kept in a world's slot. */
    public static ResourceKey<Level> worldKey(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    // ---- the raw Java door (lua/JavaDoor.java): what Lullaby's slots hold <-> Bukkit objects ----

    /** The Bukkit world for a world slot's key; null if that world is not loaded. */
    @SuppressWarnings("unchecked")
    public static org.bukkit.World bukkitWorld(Object key) {
        ServerLevel level = server().getLevel((ResourceKey<Level>) key);
        return level == null ? null : level.getWorld();
    }

    /** A Bukkit world's full id ("minecraft:overworld"), the id Lullaby's world objects use. */
    public static String worldIdOf(org.bukkit.World world) {
        return ((org.bukkit.craftbukkit.CraftWorld) world).getHandle().dimension().identifier().toString();
    }

    /** A Bukkit copy of an item stack slot's vanilla stack. */
    public static org.bukkit.inventory.ItemStack bukkitItem(Object stack) {
        return org.bukkit.craftbukkit.inventory.CraftItemStack.asBukkitCopy((net.minecraft.world.item.ItemStack) stack);
    }

    /** A vanilla copy of a Bukkit or vanilla item stack, for a new item stack slot; null for anything else. */
    public static Object vanillaItem(Object o) {
        if (o instanceof org.bukkit.inventory.ItemStack bukkit)
            return org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(bukkit);
        if (o instanceof net.minecraft.world.item.ItemStack stack) return stack.copy();
        return null;
    }

    // ---- block states (vanilla's state numbers: Block.getId / Block.stateById) ----

    /** Vanilla's block state text ("oak_stairs[facing=north]") to its state number; throws with vanilla's reason. */
    public static int blockStateParse(String text) {
        try {
            return Block.getId(BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState());
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException("block_state: " + e.getRawMessage().getString() + " in \"" + text + "\"");
        }
    }

    static BlockState state(int id) {
        BlockState state = Block.stateById(id);
        if (state == null || Block.getId(state) != id) throw new IllegalStateException("Lullaby bug: no block state " + id);
        return state;
    }

    /** "type id", then per property "name TAB kind TAB value" (kind: b boolean, i integer, e enum), one per line. */
    public static String blockStateInfo(int id) {
        BlockState state = state(id);
        StringBuilder out = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        for (Property<?> property : state.getProperties()) {
            char kind = property instanceof BooleanProperty ? 'b' : property instanceof IntegerProperty ? 'i' : 'e';
            out.append('\n').append(property.getName()).append('\t').append(kind).append('\t')
                    .append(valueName(state, property));
        }
        return out.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    /** Vanilla's own text of a state, with every property ("minecraft:oak_stairs[facing=north,half=top,...]"). */
    public static String blockStateText(int id) {
        return BlockStateParser.serialize(state(id));
    }

    /** The state with properties changed; changes are "name TAB value" lines (the library checked the kinds). */
    public static int blockStateWith(int id, String changes) {
        BlockState state = state(id);
        for (String line : changes.split("\n")) {
            int tab = line.indexOf('\t');
            String name = line.substring(0, tab);
            Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
            if (property == null) throw new IllegalArgumentException("block_state: no property " + name);
            state = with(state, property, line.substring(tab + 1));
        }
        return Block.getId(state);
    }

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> property, String value) {
        T parsed = property.getValue(value).orElseThrow(() -> new IllegalArgumentException(
                "block_state: " + property.getName() + " can't be " + value + " (possible: "
                        + String.join(", ", property.getPossibleValues().stream().map(property::getName).toList()) + ")"));
        return state.setValue(property, parsed);
    }

    /** The state number at a position; -1 if the world or chunk is not loaded (never loads a chunk). */
    public static int blockStateAt(ResourceKey<Level> world, int x, int y, int z) {
        ServerLevel level = server().getLevel(world);
        if (level == null) return -1;
        BlockState state = level.getBlockStateIfLoaded(new BlockPos(x, y, z));
        return state == null ? -1 : Block.getId(state);
    }
}
