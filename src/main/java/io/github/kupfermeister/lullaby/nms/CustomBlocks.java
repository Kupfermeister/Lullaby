package io.github.kupfermeister.lullaby.nms;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Custom blocks ({@code lullaby:...}) and changed vanilla blocks, declared with {@code register_block{ }}.
 *
 * <p><b>New blocks are real.</b> Minecraft only takes new blocks before any world exists, so they are put into
 * vanilla's block list (the block registry) by force while the plugin loads ({@link #loadLedger}). On the server they
 * are real blocks in every way: events, /setblock, saving, pistons, explosions, other plugins' registry lookups see
 * them as themselves. Scripts run later, so Lullaby keeps every declared block in a ledger, {@code
 * plugins/lullaby/blocks.txt}: a block declared for the first time exists from the next server start on (the
 * declaration says so), and a block whose declaration is gone stays as it was (an orphan), so placed blocks are kept
 * until its line is deleted from the ledger.
 *
 * <p>Each new block has a <i>carrier</i> (a vanilla block state): it copies all of the carrier's settings (hardness,
 * sounds, light, tags such as mineable/pickaxe) but not the carrier's Java behaviour (a plain block for now). Every
 * block, new or vanilla, may have an <i>appearance</i>: the vanilla state the players' games are shown (default: the
 * carrier, or the vanilla block itself).
 *
 * <ul>
 * <li><b>The disk.</b> A new block is saved in the chunk as its carrier with one extra property,
 * {@code stone[lullaby=lullaby:ruby_ore]} (plus its own properties as {@code lullaby.<name>}). Lullaby reads that back
 * as the new block; without Lullaby, vanilla reads plain stone (vanilla skips properties it doesn't know), so
 * removing the plugin leaves carriers, not holes. This needs Lullaby's own chunk codec in each world
 * ({@link #worldCreated}, before the world loads any chunk; Paper's anti-xray reads chunks with vanilla's codec, so
 * custom blocks need anti-xray off).</li>
 * <li><b>The players' games.</b> They only know vanilla's blocks: an unknown block number in a packet breaks them. A
 * hook in each player's pipeline ({@link ClientHook}) puts the appearance into every packet that shows a block:
 * chunks, block updates, block displays and endermen's held block. Break effects and block particles the server sends
 * are the block's own instead (a new block's carrier, a vanilla block itself), so they sound and look like what the
 * block really is. The game's own hit particles and hit, step and fall sounds can't be reached from the server: the
 * game makes them from the block it is shown. Not yet: falling block spawns, statistics.</li>
 * <li><b>Mining speed.</b> The game works out mining speed from the block it is shown. When the appearance mines
 * differently (hardness, needed tool, tool speed), the game and the server disagree; with {@code custom_hardness}
 * off that is refused at load (red error) and the block is shown as itself.</li>
 * <li><b>Bukkit.</b> Its Material list is fixed (a Java enum), so a new block can't have its own Material;
 * {@code getType()} gives the carrier's. Paper's block registry (BlockType) does have the new block.</li>
 * </ul>
 *
 * <p>Threads: registration at plugin load and declarations at script load, on the main thread. {@link #maps} is
 * swapped whole and only read by Netty's threads.
 */
public final class CustomBlocks {

    /** A registered new block. carrier: as registered this run (a changed carrier needs a restart). */
    public record Custom(String id, Block block, BlockState carrier) {
    }

    /** What the hook needs, swapped whole: state number → the number the game is shown / its own effects use. */
    private record Maps(int[] look, int[] effects) {
        boolean changes(int id) {
            return id >= 0 && id < look.length && look[id] != id;
        }
    }

    private static final Map<String, Custom> BY_ID = new LinkedHashMap<>();
    private static final Map<Block, Custom> BY_BLOCK = new IdentityHashMap<>();
    /** Ledger lines as on disk: id → { carrier text, appearance text }. */
    private static final Map<String, String[]> LEDGER = new LinkedHashMap<>();
    /** Block → the state its states are shown as (new blocks: from the ledger or declaration; vanilla: declared). */
    private static Map<Block, BlockState> appearance = new IdentityHashMap<>();
    private static volatile Maps maps;
    private static Path ledgerFile;
    private static final String NAME = "lullaby_custom_blocks"; // the hook's name in a player's network pipeline
    private static final String KEY = "lullaby"; // the extra property on disk
    private static final Listener LISTENER = new Listener() { };

    private CustomBlocks() {
    }

    public static Custom byBlock(Block block) {
        return BY_BLOCK.get(block);
    }

    // ---- the ledger and registration (plugin load, main thread, before any world) --------------------------------

    // FORMAT: blocks.txt, the ledger. Lines: id TAB carrier TAB appearance (vanilla's block state text); # comments.
    private static final String LEDGER_HEAD = """
            # Lullaby's new blocks (register_block{ } in scripts writes this file; don't change it while the server runs).
            # Each line: id, carrier, appearance (TAB between). A block here exists from server start on, even when no script
            # declares it anymore, so placed ones are kept. Delete its line and restart to remove it: it then loads as its carrier.
            """;

    /** Registers every block in the ledger. Problems are logged and skip that line; the rest still load. */
    public static void loadLedger(Path dataFolder, org.slf4j.Logger log) {
        ledgerFile = dataFolder.resolve("blocks.txt");
        if (!Files.exists(ledgerFile)) return;
        List<String> lines;
        try {
            lines = Files.readAllLines(ledgerFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Lullaby could not read {}; no new blocks this run", ledgerFile, e); // FORMAT
            return;
        }
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            try {
                if (p.length != 3) throw new IllegalArgumentException("needs id, carrier and appearance");
                BlockState carrier = vanillaState(p[1], "carrier");
                BlockState look = vanillaState(p[2], "appearance");
                Custom c = register(p[0], carrier);
                appearance.put(c.block, look);
                LEDGER.put(c.id, new String[] {p[1], p[2]});
            } catch (RuntimeException e) {
                log.error("Lullaby: blocks.txt line \"{}\": {}", line, e.getMessage()); // FORMAT
            }
        }
        buildMaps();
    }

    private static BlockState vanillaState(String text, String what) {
        BlockState state = Block.stateById(Nms.blockStateParse(text));
        if (BY_BLOCK.containsKey(state.getBlock())) throw new IllegalArgumentException(what + " must be a vanilla block, got " + text);
        return state;
    }

    /** Puts one new block into vanilla's block registry. Its settings are copied from the carrier's block. */
    private static Custom register(String id, BlockState carrier) {
        Identifier key = Identifier.parse(id);
        if (key.getNamespace().equals("minecraft")) throw new IllegalArgumentException("a new block can't be minecraft:, got " + id);
        if (BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("block " + id + " exists already");
        MappedRegistry<Block> registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        Block block;
        try {
            Field frozen = MappedRegistry.class.getDeclaredField("frozen");
            Field intrusive = MappedRegistry.class.getDeclaredField("unregisteredIntrusiveHolders");
            Field temporary = MappedRegistry.class.getDeclaredField("temporaryUnfrozenMap");
            frozen.setAccessible(true);
            intrusive.setAccessible(true);
            temporary.setAccessible(true);
            frozen.setBoolean(registry, false);
            intrusive.set(registry, new IdentityHashMap<>());
            try {
                ResourceKey<Block> rk = ResourceKey.create(Registries.BLOCK, key);
                block = new Block(BlockBehaviour.Properties.ofFullCopy(carrier.getBlock()).setId(rk));
                Registry.register(registry, rk, block);
            } finally {
                intrusive.set(registry, null);
                frozen.setBoolean(registry, true);
                ((Map<?, ?>) temporary.get(registry)).clear();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: can't open vanilla's block registry on this version (" + e + ")", e);
        }
        for (BlockState state : block.getStateDefinition().getPossibleStates()) {
            Block.BLOCK_STATE_REGISTRY.add(state);
            state.initCache();
        }
        copyTags(block, carrier.getBlock());
        setBukkitMaterial(block, carrier);
        Custom custom = new Custom(key.toString(), block, carrier);
        BY_ID.put(custom.id, custom);
        BY_BLOCK.put(block, custom);
        return custom;
    }

    private static void buildMaps() {
        int size = Block.BLOCK_STATE_REGISTRY.size();
        int[] look = new int[size], effects = new int[size];
        for (int i = 0; i < size; i++) look[i] = effects[i] = i;
        for (Map.Entry<Block, BlockState> e : appearance.entrySet()) {
            for (BlockState state : e.getKey().getStateDefinition().getPossibleStates()) look[Block.getId(state)] = Block.getId(e.getValue());
        }
        for (Custom c : BY_ID.values()) {
            for (BlockState state : c.block.getStateDefinition().getPossibleStates()) effects[Block.getId(state)] = Block.getId(c.carrier);
        }
        maps = new Maps(look, effects);
    }

    /**
     * A new block gets the same tags as its carrier (mineable/pickaxe and so on: tools, fire, mobs read them).
     * Vanilla only fills tags from data packs, which never name new blocks, so this runs again after each data pack
     * reload (vanilla empties them then). Before the data packs are loaded the tags are empty but bound.
     */
    private static void copyTags(Block block, Block carrier) {
        List<TagKey<Block>> tags;
        try {
            tags = carrier.builtInRegistryHolder().tags().toList();
        } catch (IllegalStateException | NullPointerException notBoundYet) {
            tags = List.of();
        }
        try {
            Method bind = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
            bind.setAccessible(true);
            bind.invoke(block.builtInRegistryHolder(), tags);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: can't set a new block's tags on this version (" + e + ")", e);
        }
    }

    /** Paper keeps each state's Bukkit Material in a field; a new block's states get their carrier's. */
    private static void setBukkitMaterial(Block block, BlockState carrier) {
        try {
            Field f = BlockState.class.getDeclaredField("cachedMaterial");
            f.setAccessible(true);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) f.set(state, carrier.getBukkitMaterial());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: can't set a new block's Bukkit type on this version (" + e + ")", e);
        }
    }

    // ---- declarations (each script load, main thread) ---------------------------------------------------------

    /**
     * register_block{ } of all scripts, once they have loaded. {@code text}: one declaration per line, "id TAB
     * carrier TAB appearance TAB file TAB line" (carrier and appearance: block state text or empty). Gives back the
     * problems, one per line: "error|warning TAB file TAB line TAB message". Applies what can change now (appearance),
     * writes the ledger for what needs a restart (new blocks, other carriers), and resends the chunks if what
     * players see changed. {@code customHardness}: config custom_hardness (BreakSpeed).
     */
    public static String declare(String text, boolean customHardness) {
        List<String> problems = new ArrayList<>();
        Map<Block, BlockState> look = new IdentityHashMap<>();
        Set<String> seen = new HashSet<>();
        boolean ledgerChanged = false;
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            String[] p = line.split("\t", -1);
            String file = p[3], where = p[4];
            try {
                Identifier key = Identifier.parse(p[0]);
                String id = key.toString();
                if (!seen.add(id)) throw new IllegalArgumentException(id + " is declared twice");
                Custom c = BY_ID.get(id);
                Block vanilla = c == null ? BuiltInRegistries.BLOCK.getOptional(key).orElse(null) : null;
                if (c == null && vanilla != null) {
                    // A vanilla block: it is its own carrier; only what can change on it is set.
                    if (!p[1].isEmpty()) throw new IllegalArgumentException(id + " is a vanilla block, so it is its own carrier (leave carrier out)");
                    if (!p[2].isEmpty()) {
                        BlockState a = vanillaState(p[2], "appearance");
                        String mismatch = customHardness ? null : mismatch(vanilla.defaultBlockState(), a);
                        if (mismatch != null) problems.add(problem("error", file, where, id + " looks like " + p[2] + ", but " + mismatch + HARDNESS_OFF + " it is shown as itself."));
                        else look.put(vanilla, a);
                    }
                    continue;
                }
                if (p[1].isEmpty()) throw new IllegalArgumentException(id + " is a new block: it needs carrier = a vanilla block state");
                if (key.getNamespace().equals("minecraft")) {
                    throw new IllegalArgumentException(id + " is not a vanilla block; a new block needs its own namespace, like lullaby:" + key.getPath());
                }
                BlockState carrier = vanillaState(p[1], "carrier");
                String carrierText = Nms.blockStateText(Block.getId(carrier));
                BlockState a = p[2].isEmpty() ? carrier : vanillaState(p[2], "appearance");
                String appearanceText = Nms.blockStateText(Block.getId(a));
                String[] old = LEDGER.get(id);
                if (old == null || !old[0].equals(carrierText) || !old[1].equals(appearanceText)) {
                    LEDGER.put(id, new String[] {carrierText, appearanceText});
                    ledgerChanged = true;
                }
                if (c == null) {
                    problems.add(problem("warning", file, where, id + " is new: restart the server so it exists (Minecraft only takes new blocks at start)."));
                    continue;
                }
                if (c.carrier != carrier) {
                    problems.add(problem("warning", file, where, id + ": the carrier changes from " + Nms.blockStateText(Block.getId(c.carrier))
                            + " to " + carrierText + " at the next server start; until then it stays."));
                }
                String mismatch = customHardness ? null : mismatch(c.block.defaultBlockState(), a);
                if (mismatch != null) {
                    problems.add(problem("error", file, where, id + " looks like " + appearanceText + ", but " + mismatch + HARDNESS_OFF
                            + " it is shown as its carrier."));
                    look.put(c.block, c.carrier);
                } else {
                    look.put(c.block, a);
                }
            } catch (RuntimeException e) {
                problems.add(problem("error", file, where, "register_block{}: " + e.getMessage()));
            }
        }
        // Orphans: registered blocks no script declares now keep their last appearance (checked like declared ones).
        for (Custom c : BY_ID.values()) {
            if (look.containsKey(c.block)) continue;
            BlockState a = appearance.getOrDefault(c.block, c.carrier);
            if (!customHardness && mismatch(c.block.defaultBlockState(), a) != null) a = c.carrier;
            look.put(c.block, a);
        }
        if (ledgerChanged) writeLedger(problems);
        boolean changed = !look.equals(appearance);
        appearance = look;
        buildMaps();
        if (changed) resendChunks();
        return String.join("\n", problems);
    }

    private static final String HARDNESS_OFF = ", so the game would mine it at another speed than the server."
            + " custom_hardness is off in the config: until it is on or the appearance mines the same,";

    private static String problem(String kind, String file, String line, String message) {
        return kind + "\t" + file + "\t" + line + "\t" + message.replace('\n', ' ');
    }

    /**
     * Why the game, shown {@code look}, would mine the real block {@code real} at another speed, or null if it
     * mines the same. Vanilla's inputs that depend on the block: its hardness, whether it needs the right tool for
     * drops, and every tool's rules (which blocks a tool is fast on or right for: read from every item's tool
     * component, so no list of tags is written here).
     */
    private static String mismatch(BlockState real, BlockState look) {
        float hr = real.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        float hl = look.getDestroySpeed(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (hr != hl) return "its hardness is " + Codecs.numberText(hr) + " and the appearance's is " + Codecs.numberText(hl);
        if (real.requiresCorrectToolForDrops() != look.requiresCorrectToolForDrops()) {
            return (real.requiresCorrectToolForDrops() ? "it needs" : "it doesn't need") + " the right tool and the appearance "
                    + (look.requiresCorrectToolForDrops() ? "does" : "doesn't");
        }
        Holder<Block> hReal = real.getBlock().builtInRegistryHolder(), hLook = look.getBlock().builtInRegistryHolder();
        for (Item item : BuiltInRegistries.ITEM) {
            Tool tool = item.components().get(DataComponents.TOOL);
            if (tool == null) continue;
            for (Tool.Rule rule : tool.rules()) {
                HolderSet<Block> set = rule.blocks();
                if (set.contains(hReal) != set.contains(hLook)) {
                    return BuiltInRegistries.ITEM.getKey(item) + " mines " + (set.contains(hReal) ? "it" : "the appearance")
                            + " differently than " + (set.contains(hReal) ? "the appearance" : "it");
                }
            }
        }
        return null;
    }

    private static void writeLedger(List<String> problems) {
        StringBuilder out = new StringBuilder(LEDGER_HEAD);
        for (Map.Entry<String, String[]> e : LEDGER.entrySet()) {
            out.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\t').append(e.getValue()[1]).append('\n');
        }
        try {
            Path tmp = ledgerFile.resolveSibling("blocks.txt.tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            Files.move(tmp, ledgerFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            problems.add(problem("error", "plugins/lullaby/blocks.txt", "-1", "could not write the block ledger: " + e.getMessage()));
        }
    }

    /** Sends every loaded chunk a player can see again (after appearances changed on reload). */
    private static void resendChunks() {
        Map<org.bukkit.World, Set<Long>> done = new HashMap<>();
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            org.bukkit.World w = p.getWorld();
            Set<Long> set = done.computeIfAbsent(w, k -> new HashSet<>());
            int r = p.getViewDistance(), cx = p.getLocation().getBlockX() >> 4, cz = p.getLocation().getBlockZ() >> 4;
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    if (w.isChunkLoaded(x, z) && set.add(((long) x << 32) ^ (z & 0xFFFFFFFFL))) w.refreshChunk(x, z);
                }
            }
        }
    }

    // ---- turning on (plugin enable, before the worlds load) ------------------------------------------------------

    /**
     * Puts Lullaby's chunk codec into each world as it is created (only when new blocks exist), the client hook into
     * each player, and the tags back after each data pack reload. Runs before the first world loads (Lullaby loads
     * at STARTUP for this).
     */
    public static void start(Plugin plugin, Path dataFolder) {
        if (ledgerFile == null) ledgerFile = dataFolder.resolve("blocks.txt");
        if (maps == null) buildMaps();
        if (!BY_ID.isEmpty()) {
            Bukkit.getPluginManager().registerEvent(WorldInitEvent.class, LISTENER, EventPriority.LOWEST, (l, e) -> {
                if (e instanceof WorldInitEvent init) worldCreated(((org.bukkit.craftbukkit.CraftWorld) init.getWorld()).getHandle());
            }, plugin);
            for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) worldCreated(level);
        }
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, LISTENER, EventPriority.LOWEST, (l, e) -> {
            if (e instanceof PlayerJoinEvent join) hook(((org.bukkit.craftbukkit.entity.CraftPlayer) join.getPlayer()).getHandle());
        }, plugin);
        Bukkit.getPluginManager().registerEvent(ServerResourcesReloadedEvent.class, LISTENER, EventPriority.MONITOR, (l, e) -> {
            for (Custom c : BY_ID.values()) copyTags(c.block, c.carrier.getBlock());
        }, plugin);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) hook(player);
    }

    /** Data packs are loaded now (the server is up): the tags of the carriers can be read. */
    public static void serverLoaded() {
        for (Custom c : BY_ID.values()) copyTags(c.block, c.carrier.getBlock());
    }

    /** Swaps the world's chunk codec for block states with {@link #CODEC} (the world's field is final; reflection). */
    private static void worldCreated(Level level) {
        try {
            Field f = Level.class.getDeclaredField("palettedContainerFactory");
            f.setAccessible(true);
            PalettedContainerFactory old = (PalettedContainerFactory) f.get(level);
            RecordComponent[] parts = PalettedContainerFactory.class.getRecordComponents();
            Object[] values = new Object[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                values[i] = parts[i].getName().equals("blockStatesContainerCodec")
                        ? PalettedContainer.codecRW(CODEC, old.blockStatesStrategy(), old.defaultBlockState())
                        : parts[i].getAccessor().invoke(old);
            }
            f.set(level, PalettedContainerFactory.class.getDeclaredConstructor(types).newInstance(values));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: can't give a world Lullaby's chunk codec on this version (" + e + ")", e);
        }
    }

    // ---- the disk: carrier[lullaby=id] -------------------------------------------------------------------------

    /**
     * Vanilla's block state codec, except that a new block's state is written as its carrier with {@code lullaby=<id>}
     * (and its own properties as {@code lullaby.<name>}) and read back from that. An id that isn't registered (its
     * ledger line was deleted) reads as the carrier.
     */
    static final Codec<BlockState> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<T> encode(BlockState state, DynamicOps<T> ops, T prefix) {
            Custom c = BY_BLOCK.get(state.getBlock());
            if (c == null) return BlockState.CODEC.encode(state, ops, prefix);
            return BlockState.FULL_CODEC.encodeStart(ops, c.carrier).flatMap(map -> {
                T props = ops.get(map, "properties").result().orElseGet(ops::emptyMap);
                props = ops.set(props, KEY, ops.createString(c.id));
                for (var e : state.getValues().toList()) {
                    props = ops.set(props, KEY + "." + e.property().getName(), ops.createString(e.valueName()));
                }
                return ops.mergeToPrimitive(prefix, ops.set(map, "properties", props));
            });
        }

        @Override
        public <T> DataResult<Pair<BlockState, T>> decode(DynamicOps<T> ops, T input) {
            Optional<T> props = ops.get(input, "properties").result();
            if (props.isPresent()) {
                Optional<String> id = ops.get(props.get(), KEY).flatMap(ops::getStringValue).result();
                if (id.isPresent()) {
                    Custom c = BY_ID.get(id.get());
                    if (c != null) {
                        BlockState state = c.block.defaultBlockState();
                        for (Property<?> p : c.block.getStateDefinition().getProperties()) {
                            Optional<String> v = ops.get(props.get(), KEY + "." + p.getName()).flatMap(ops::getStringValue).result();
                            if (v.isPresent()) state = with(state, p, v.get());
                        }
                        return DataResult.success(Pair.of(state, ops.empty()));
                    }
                }
            }
            return BlockState.CODEC.decode(ops, input);
        }
    };

    private static <T extends Comparable<T>> BlockState with(BlockState state, Property<T> p, String value) {
        return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
    }

    // ---- the players' games -----------------------------------------------------------------------------------

    private static void hook(ServerPlayer player) {
        Channel channel = player.connection.connection.channel;
        if (channel == null) return;
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null) return;
        try {
            // Right after the unbundler: the last of Lullaby's hooks on the way out, so packets the other hooks add
            // (fake blocks, break effects) pass here too. Bundles are still whole here.
            if (pipeline.get(HandlerNames.UNBUNDLER) != null) pipeline.addAfter(HandlerNames.UNBUNDLER, NAME, HOOK);
            else if (pipeline.get(HandlerNames.PACKET_HANDLER) != null) pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, HOOK);
        } catch (NoSuchElementException | IllegalArgumentException ignored) {
            // closed in between
        }
    }

    private static final ClientHook HOOK = new ClientHook();

    /** Netty's threads; reads only {@link #maps}. Leaves every packet alone that shows no changed block. */
    @ChannelHandler.Sharable
    private static final class ClientHook extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Maps m = maps;
            if (m != null && msg instanceof Packet<?> p) msg = rewrite(p, m);
            super.write(ctx, msg, promise);
        }
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> rewrite(Packet<?> packet, Maps m) {
        int[] look = m.look, fx = m.effects;
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk -> rewriteChunk(chunk.chunkData(), m);
            case ClientboundBlockUpdatePacket u -> {
                int id = Block.getId(u.getBlockState());
                if (m.changes(id)) return new ClientboundBlockUpdatePacket(u.getPos(), Block.stateById(look[id]));
            }
            case ClientboundSectionBlocksUpdatePacket u -> {
                boolean[] changed = {false};
                Short2ObjectOpenHashMap<BlockState> out = new Short2ObjectOpenHashMap<>();
                SectionPos[] section = {null};
                u.runUpdates((pos, state) -> {
                    if (section[0] == null) section[0] = SectionPos.of(pos);
                    int id = Block.getId(state);
                    if (m.changes(id)) {
                        changed[0] = true;
                        state = Block.stateById(look[id]);
                    }
                    out.put(SectionPos.sectionRelativePos(pos), state);
                });
                if (changed[0]) return new ClientboundSectionBlocksUpdatePacket(section[0], out);
            }
            // Break effects: the block's own (a new block's carrier), not its appearance.
            case ClientboundLevelEventPacket e when e.getType() == 2001 && e.getData() >= 0 && e.getData() < fx.length && fx[e.getData()] != e.getData() -> {
                return new ClientboundLevelEventPacket(2001, e.getPos(), fx[e.getData()], e.isGlobalEvent());
            }
            case ClientboundLevelParticlesPacket p when p.particle() instanceof BlockParticleOption b
                    && fx[Block.getId(b.getState())] != Block.getId(b.getState()) -> {
                BlockParticleOption swapped = new BlockParticleOption(b.getType(), Block.stateById(fx[Block.getId(b.getState())]));
                return new ClientboundLevelParticlesPacket(swapped, p.overrideLimiter(), p.alwaysShow(), p.x(), p.y(), p.z(), p.xDist(),
                        p.yDist(), p.zDist(), p.xMaxSpeed(), p.yMaxSpeed(), p.zMaxSpeed(), p.count(), p.randomizationType());
            }
            case ClientboundSetEntityDataPacket d -> {
                List<SynchedEntityData.DataValue<?>> out = null;
                List<SynchedEntityData.DataValue<?>> items = d.packedItems();
                for (int i = 0; i < items.size(); i++) {
                    SynchedEntityData.DataValue<?> v = items.get(i);
                    SynchedEntityData.DataValue<?> nv = v;
                    if (v.serializer() == EntityDataSerializers.BLOCK_STATE && v.value() instanceof BlockState s && m.changes(Block.getId(s))) {
                        nv = new SynchedEntityData.DataValue<>(v.id(), EntityDataSerializers.BLOCK_STATE, Block.stateById(look[Block.getId(s)]));
                    } else if (v.serializer() == EntityDataSerializers.OPTIONAL_BLOCK_STATE && v.value() instanceof Optional<?> o
                            && o.orElse(null) instanceof BlockState s && m.changes(Block.getId(s))) {
                        nv = new SynchedEntityData.DataValue<>(v.id(), EntityDataSerializers.OPTIONAL_BLOCK_STATE, Optional.of(Block.stateById(look[Block.getId(s)])));
                    }
                    if (nv != v && out == null) out = new ArrayList<>(items);
                    if (out != null) out.set(i, nv);
                }
                if (out != null) return new ClientboundSetEntityDataPacket(d.id(), out);
            }
            case ClientboundBundlePacket bundle -> {
                List<Packet<? super ClientGamePacketListener>> out = new ArrayList<>();
                boolean changed = false;
                for (Packet<? super ClientGamePacketListener> sub : bundle.subPackets()) {
                    Packet<? super ClientGamePacketListener> r = (Packet<? super ClientGamePacketListener>) rewrite(sub, m);
                    changed |= r != sub;
                    out.add(r);
                }
                if (changed) return new ClientboundBundlePacket(out);
            }
            default -> { }
        }
        return packet;
    }

    // ---- chunk packets ------------------------------------------------------------------------------------------

    private static final Field CHUNK_BUFFER;

    static {
        try {
            CHUNK_BUFFER = ClientboundLevelChunkPacketData.class.getDeclaredField("buffer");
            CHUNK_BUFFER.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: chunk packet layout changed on this version (" + e + ")", e);
        }
    }

    /**
     * Rewrites the sections of a chunk packet in place (the packet may be shared by several players; rewriting
     * twice gives the same result, since appearances are vanilla blocks that aren't changed themselves... unless a
     * vanilla block is shown as another changed vanilla block: then the second pass would change it again, so a
     * rewritten buffer is remembered and never rewritten twice). Section layout (LevelChunkSection.write): two
     * shorts (block and fluid counts), the block states, then the biomes; each is one byte of bits per entry, the
     * palette (bits 0: one number; up to 8 for blocks / 3 for biomes: a count and that many numbers; more: none, the
     * entries are the global numbers), then the packed entries as longs.
     */
    private static void rewriteChunk(ClientboundLevelChunkPacketData data, Maps m) {
        try {
            synchronized (data) {
                byte[] in = (byte[]) CHUNK_BUFFER.get(data);
                if (DONE.containsKey(in) || !hasChanged(in, m)) return;
                byte[] out = rewriteSections(in, m.look);
                DONE.put(out, Boolean.TRUE);
                CHUNK_BUFFER.set(data, out);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Buffers this hook made (weak: freed with their packets). */
    private static final Map<byte[], Boolean> DONE = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static boolean hasChanged(byte[] in, Maps m) {
        Reader r = new Reader(in);
        while (r.p < in.length) {
            r.p += 4;
            int bits = r.u8();
            if (bits == 0) {
                if (m.changes(r.varInt())) return true;
            } else if (bits <= 8) {
                int n = r.varInt();
                for (int i = 0; i < n; i++) if (m.changes(r.varInt())) return true;
                r.p += longs(4096, bits) * 8;
            } else {
                int count = longs(4096, bits);
                int per = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < count; i++) {
                    long v = r.i64();
                    for (int j = 0; j < per; j++) if (m.changes((int) ((v >>> (j * bits)) & mask))) return true;
                }
            }
            r.skipBiomes();
        }
        return false;
    }

    private static byte[] rewriteSections(byte[] in, int[] remap) {
        Reader r = new Reader(in);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length + 64);
        while (r.p < in.length) {
            out.write(in, r.p, 4);
            r.p += 4;
            int bits = r.u8();
            out.write(bits);
            if (bits == 0) {
                writeVarInt(out, map(remap, r.varInt()));
            } else if (bits <= 8) {
                int n = r.varInt();
                writeVarInt(out, n);
                for (int i = 0; i < n; i++) writeVarInt(out, map(remap, r.varInt()));
                int len = longs(4096, bits) * 8;
                out.write(in, r.p, len);
                r.p += len;
            } else {
                int count = longs(4096, bits);
                int per = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < count; i++) {
                    long v = r.i64(), nv = 0;
                    for (int j = 0; j < per; j++) {
                        long e = (v >>> (j * bits)) & mask;
                        nv |= ((long) map(remap, (int) e) & mask) << (j * bits);
                    }
                    for (int b = 7; b >= 0; b--) out.write((int) (nv >>> (b * 8)));
                }
            }
            int start = r.p;
            r.skipBiomes();
            out.write(in, start, r.p - start);
        }
        return out.toByteArray();
    }

    private static int map(int[] remap, int id) {
        return id >= 0 && id < remap.length ? remap[id] : id;
    }

    private static int longs(int entries, int bits) {
        int per = 64 / bits;
        return (entries + per - 1) / per;
    }

    private static void writeVarInt(java.io.ByteArrayOutputStream out, int v) {
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    /** Reads the section bytes (big-endian longs, var-ints as vanilla writes them). */
    private static final class Reader {
        final byte[] b;
        int p;

        Reader(byte[] b) {
            this.b = b;
        }

        int u8() {
            return b[p++] & 0xFF;
        }

        int varInt() {
            int v = 0, shift = 0, x;
            do {
                x = b[p++];
                v |= (x & 0x7F) << shift;
                shift += 7;
            } while ((x & 0x80) != 0);
            return v;
        }

        long i64() {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (b[p++] & 0xFF);
            return v;
        }

        void skipBiomes() {
            int bits = u8();
            if (bits == 0) {
                varInt();
            } else {
                if (bits <= 3) {
                    int n = varInt();
                    for (int i = 0; i < n; i++) varInt();
                }
                p += longs(64, bits) * 8;
            }
        }
    }
}
