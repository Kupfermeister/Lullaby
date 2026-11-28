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
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import org.bukkit.event.world.WorldInitEvent;
import org.bukkit.plugin.Plugin;

/**
 * PROTOTYPE: real custom blocks ({@code lullaby:...}). Minecraft can't add blocks once it has started, so they are put
 * into vanilla's block list (the block registry) by force while the plugin loads, before any world exists. On the
 * server they are real blocks in every way (events, /setblock, saving, pistons, explosions see them as themselves).
 * Three things keep the rest of the world from noticing:
 *
 * <ul>
 * <li><b>The disk.</b> A custom block is saved in the chunk as its <i>carrier</i> with one extra property,
 * {@code stone[lullaby=lullaby:test_block]} (plus its own properties as {@code lullaby.<name>}). Lullaby reads that
 * back as the custom block; without Lullaby, vanilla reads it as plain stone (vanilla skips properties it doesn't
 * know), so removing the plugin leaves carriers, not holes. This needs Lullaby's own chunk codec in each world
 * ({@link #worldCreated}, before the world loads any chunk; Paper's anti-xray reads chunks with vanilla's codec, so
 * custom blocks need anti-xray off).</li>
 * <li><b>The players' games.</b> They only know vanilla's blocks: an unknown block number in a packet breaks them. A
 * hook in each player's pipeline ({@link ClientHook}) turns every custom state into its <i>appearance</i> (a vanilla
 * state) in every packet that carries block states: chunks, block updates, break effects, block particles, entity
 * data (block displays, endermen). Not yet: falling block spawns, statistics, block events (custom blocks have none).</li>
 * <li><b>Bukkit.</b> It has no Material for a custom block, so {@code getType()} gives the carrier's.</li>
 * </ul>
 *
 * <p>A custom block copies all of its carrier's settings (hardness, sounds, light, tags such as mineable/pickaxe) but
 * not its Java behaviour: it is a plain block for now. Later: other custom sounds and particles per block
 * ({@code register_block{}} fields), loot tables, the orphan ledger, per-player appearance.
 *
 * <p>Threads: registration on the main thread at plugin load; {@link #REMAP} is written once there and only read
 * afterwards (Netty's threads read it in the hook).
 */
public final class CustomBlocks {

    /** A registered custom block. */
    public record Custom(String id, Block block, BlockState carrier, BlockState appearance) {
    }

    private static final Map<String, Custom> BY_ID = new LinkedHashMap<>();
    private static final Map<Block, Custom> BY_BLOCK = new IdentityHashMap<>();
    /** Block state number → the number the players' games get. Identity for vanilla states; null: no custom blocks. */
    private static volatile int[] REMAP;
    private static int vanillaStates; // custom states are numbered from here on
    private static final String NAME = "lullaby_custom_blocks"; // the hook's name in a player's network pipeline
    private static final String KEY = "lullaby"; // the extra property on disk
    private static final Listener LISTENER = new Listener() { };

    private CustomBlocks() {
    }

    /** True once any custom block exists (then the codec and the client hook are needed). */
    public static boolean any() {
        return !BY_ID.isEmpty();
    }

    public static Custom byBlock(Block block) {
        return BY_BLOCK.get(block);
    }

    // ---- registration (plugin load, main thread, before any world) -------------------------------------------

    /**
     * Registers one custom block. {@code id}: "namespace:path" (not minecraft). Its settings are copied from the
     * carrier's block. Throws with a clear message on a bad id or when the registry can't be opened.
     */
    public static Custom register(String id, BlockState carrier, BlockState appearance) {
        Identifier key = Identifier.parse(id);
        if (key.getNamespace().equals("minecraft")) throw new IllegalArgumentException("a custom block id can't be minecraft:, got " + id);
        if (BuiltInRegistries.BLOCK.containsKey(key)) throw new IllegalArgumentException("block " + id + " exists already");
        MappedRegistry<Block> registry = (MappedRegistry<Block>) BuiltInRegistries.BLOCK;
        if (vanillaStates == 0) vanillaStates = Block.BLOCK_STATE_REGISTRY.size();
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
        Custom custom = new Custom(key.toString(), block, carrier, appearance);
        BY_ID.put(custom.id, custom);
        BY_BLOCK.put(block, custom);
        buildRemap();
        return custom;
    }

    private static void buildRemap() {
        int[] remap = new int[Block.BLOCK_STATE_REGISTRY.size()];
        for (int i = 0; i < remap.length; i++) remap[i] = i;
        for (Custom c : BY_ID.values()) {
            for (BlockState state : c.block.getStateDefinition().getPossibleStates()) {
                remap[Block.getId(state)] = Block.getId(c.appearance);
            }
        }
        REMAP = remap;
    }

    /**
     * A custom block gets the same tags as its carrier (mineable/pickaxe and so on: tools, fire, mobs read them).
     * Vanilla only fills tags from data packs, which never name custom blocks, so this runs again after each data
     * pack reload (vanilla empties them then). Before the data packs are loaded the tags are empty but bound.
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
            throw new IllegalStateException("Lullaby bug: can't set a custom block's tags on this version (" + e + ")", e);
        }
    }

    /** Paper keeps each state's Bukkit Material in a field; a custom state gets its carrier's. */
    private static void setBukkitMaterial(Block block, BlockState carrier) {
        try {
            Field f = BlockState.class.getDeclaredField("cachedMaterial");
            f.setAccessible(true);
            for (BlockState state : block.getStateDefinition().getPossibleStates()) f.set(state, carrier.getBukkitMaterial());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Lullaby bug: can't set a custom block's Bukkit type on this version (" + e + ")", e);
        }
    }

    // ---- turning on (plugin enable, before the worlds load) ------------------------------------------------------

    /**
     * Puts Lullaby's chunk codec into each world as it is created, the client hook into each player, and the tags
     * back after each data pack reload. Must run before the first world loads (Lullaby loads at STARTUP for this).
     */
    public static void start(Plugin plugin) {
        if (!any()) return;
        Bukkit.getPluginManager().registerEvent(WorldInitEvent.class, LISTENER, EventPriority.LOWEST, (l, e) -> {
            if (e instanceof WorldInitEvent init) worldCreated(((org.bukkit.craftbukkit.CraftWorld) init.getWorld()).getHandle());
        }, plugin);
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, LISTENER, EventPriority.LOWEST, (l, e) -> {
            if (e instanceof PlayerJoinEvent join) hook(((org.bukkit.craftbukkit.entity.CraftPlayer) join.getPlayer()).getHandle());
        }, plugin);
        Bukkit.getPluginManager().registerEvent(ServerResourcesReloadedEvent.class, LISTENER, EventPriority.MONITOR, (l, e) -> {
            for (Custom c : BY_ID.values()) copyTags(c.block, c.carrier.getBlock());
        }, plugin);
        for (ServerLevel level : MinecraftServer.getServer().getAllLevels()) worldCreated(level);
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
     * Vanilla's block state codec, except that a custom state is written as its carrier with {@code lullaby=<id>}
     * (and its own properties as {@code lullaby.<name>}) and read back from that. An unknown id (a block whose
     * declaration was removed) reads as the carrier for now (later: the orphan ledger keeps it).
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
                        for (var p : c.block.getStateDefinition().getProperties()) {
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

    private static <T extends Comparable<T>> BlockState with(BlockState state, net.minecraft.world.level.block.state.properties.Property<T> p, String value) {
        return p.getValue(value).map(v -> state.setValue(p, v)).orElse(state);
    }

    // ---- the players' games: every custom state becomes its appearance ---------------------------------------

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

    /** Netty's threads; reads only REMAP. Changes no packet that holds no custom state. */
    @ChannelHandler.Sharable
    private static final class ClientHook extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            int[] remap = REMAP;
            if (remap != null && msg instanceof Packet<?> p) msg = rewrite(p, remap);
            super.write(ctx, msg, promise);
        }
    }

    @SuppressWarnings("unchecked")
    private static Packet<?> rewrite(Packet<?> packet, int[] remap) {
        switch (packet) {
            case ClientboundLevelChunkWithLightPacket chunk -> rewriteChunk(chunk.chunkData(), remap);
            case ClientboundBlockUpdatePacket u -> {
                int id = Block.getId(u.getBlockState());
                if (id >= vanillaStates) return new ClientboundBlockUpdatePacket(u.getPos(), Block.stateById(remap[id]));
            }
            case ClientboundSectionBlocksUpdatePacket u -> {
                boolean[] custom = {false};
                Short2ObjectOpenHashMap<BlockState> out = new Short2ObjectOpenHashMap<>();
                SectionPos[] section = {null};
                u.runUpdates((pos, state) -> {
                    if (section[0] == null) section[0] = SectionPos.of(pos);
                    int id = Block.getId(state);
                    if (id >= vanillaStates) {
                        custom[0] = true;
                        state = Block.stateById(remap[id]);
                    }
                    out.put(SectionPos.sectionRelativePos(pos), state);
                });
                if (custom[0]) return new ClientboundSectionBlocksUpdatePacket(section[0], out);
            }
            case ClientboundLevelEventPacket e when e.getType() == 2001 && e.getData() >= vanillaStates && e.getData() < remap.length ->
                    {
                        return new ClientboundLevelEventPacket(2001, e.getPos(), remap[e.getData()], e.isGlobalEvent());
                    }
            case ClientboundLevelParticlesPacket p when p.particle() instanceof BlockParticleOption b && Block.getId(b.getState()) >= vanillaStates -> {
                BlockParticleOption swapped = new BlockParticleOption(b.getType(), Block.stateById(remap[Block.getId(b.getState())]));
                return new ClientboundLevelParticlesPacket(swapped, p.overrideLimiter(), p.alwaysShow(), p.x(), p.y(), p.z(), p.xDist(),
                        p.yDist(), p.zDist(), p.xMaxSpeed(), p.yMaxSpeed(), p.zMaxSpeed(), p.count(), p.randomizationType());
            }
            case ClientboundSetEntityDataPacket d -> {
                List<SynchedEntityData.DataValue<?>> out = null;
                List<SynchedEntityData.DataValue<?>> items = d.packedItems();
                for (int i = 0; i < items.size(); i++) {
                    SynchedEntityData.DataValue<?> v = items.get(i);
                    SynchedEntityData.DataValue<?> nv = v;
                    if (v.serializer() == EntityDataSerializers.BLOCK_STATE && v.value() instanceof BlockState s && Block.getId(s) >= vanillaStates) {
                        nv = new SynchedEntityData.DataValue<>(v.id(), EntityDataSerializers.BLOCK_STATE, Block.stateById(remap[Block.getId(s)]));
                    } else if (v.serializer() == EntityDataSerializers.OPTIONAL_BLOCK_STATE && v.value() instanceof Optional<?> o
                            && o.orElse(null) instanceof BlockState s && Block.getId(s) >= vanillaStates) {
                        nv = new SynchedEntityData.DataValue<>(v.id(), EntityDataSerializers.OPTIONAL_BLOCK_STATE, Optional.of(Block.stateById(remap[Block.getId(s)])));
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
                    Packet<? super ClientGamePacketListener> r = (Packet<? super ClientGamePacketListener>) rewrite(sub, remap);
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
     * twice gives the same result, since appearances are vanilla). Section layout (LevelChunkSection.write): two
     * shorts (block and fluid counts), the block states, then the biomes; each is one byte of bits per entry, the
     * palette (bits 0: one number; up to 8 for blocks / 3 for biomes: a count and that many numbers; more: none, the
     * entries are the global numbers), then the packed entries as longs. Only numbers of custom states change, so
     * every length stays except a palette number's own length (numbers are written in 1 to 5 bytes).
     */
    private static void rewriteChunk(ClientboundLevelChunkPacketData data, int[] remap) {
        try {
            byte[] in = (byte[]) CHUNK_BUFFER.get(data);
            if (!hasCustom(in, remap.length)) return;
            CHUNK_BUFFER.set(data, rewriteSections(in, remap));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean hasCustom(byte[] in, int limit) {
        Reader r = new Reader(in);
        while (r.p < in.length) {
            r.p += 4;
            int bits = r.u8();
            if (bits == 0) {
                if (r.varInt() >= vanillaStates) return true;
            } else if (bits <= 8) {
                int n = r.varInt();
                for (int i = 0; i < n; i++) if (r.varInt() >= vanillaStates) return true;
                r.p += longs(4096, bits) * 8;
            } else {
                int count = longs(4096, bits);
                int per = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < count; i++) {
                    long v = r.i64();
                    for (int j = 0; j < per; j++) if (((v >>> (j * bits)) & mask) >= vanillaStates) return true;
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
