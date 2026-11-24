package io.github.kupfermeister.lullaby.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * The block engine: shows {@link BlockEdit}s as fake blocks and writes them into the world, both for edits of any
 * size. LullaBlend uses it; it is meant for every big block job (a later {@code fill{}} action, pasting schematics).
 *
 * <p><b>Show</b> ({@link #show}): a layer of fake blocks only its viewers' games see. The world stays untouched and
 * the server knows nothing of the fake blocks. A hook in each viewer's network pipeline puts the fake blocks back
 * into every chunk the server sends again and into the server's own block updates there, so they never vanish.
 * Changed sections are sent as vanilla's section update packets, nearest first, a few per tick per viewer
 * ({@code block_show_sections} in the config). The game then lights them itself. Viewers may fly while they see a
 * layer (standing on fake blocks is flying, to the server).
 *
 * <p><b>Write</b> ({@link #write}): the blocks go straight into the chunk sections, like FAWE: no block updates,
 * no physics, no neighbor changes, so water doesn't flow and sand doesn't fall until something touches them.
 * Chunks load in the background (Paper); writing runs on the main thread for at most {@code block_write_ms} per
 * tick; then the light is computed again (Paper's light engine, its own threads) and the chunks are sent anew. The
 * old blocks come back as an undo edit. Block entities (chests, signs) at written cells are removed, new ones are
 * made empty. Villager workstations and beds written this way are not known to villagers until a block update.
 *
 * <p>Threads: everything here runs on the main thread except: {@link #work} runs its job on the one block worker
 * thread (which only builds edits from data it owns), the light callback hands its chunk to the main thread, and
 * the pipeline hook reads each viewer's current fake blocks (an unchangeable snapshot) on Netty's threads.
 */
public final class BlockEngine {

    private BlockEngine() {
    }

    private static Plugin plugin;
    private static int writeMs = 10;
    private static int showSections = 128;
    private static final ConcurrentLinkedQueue<Runnable> TO_MAIN = new ConcurrentLinkedQueue<>();
    private static ExecutorService worker;

    /** Turns the engine on (plugin start). It lives as long as the plugin, so it outlasts reloads. */
    public static void start(Plugin owner) {
        plugin = owner;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Lullaby block worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** Plugin stop: every layer goes (the games get the real blocks back), unfinished writes stop where they are. */
    public static void stop() {
        for (Layer layer : new ArrayList<>(LAYERS)) hide(layer);
        for (View view : VIEWS.values()) unhook(view);
        VIEWS.clear();
        JOBS.clear();
        if (worker != null) worker.shutdownNow();
    }

    /** Config: most milliseconds of writing per tick, most fake sections sent to one viewer per tick. */
    public static void configure(int writeMsPerTick, int showSectionsPerTick) {
        writeMs = Math.max(1, writeMsPerTick);
        showSections = Math.max(1, showSectionsPerTick);
    }

    /** Runs {@code job} on the block worker thread, then {@code onMain} with its result on the main thread. */
    public static <T> void work(Supplier<T> job, Consumer<T> onMain, Consumer<RuntimeException> onError) {
        worker.execute(() -> {
            try {
                T result = job.get();
                TO_MAIN.add(() -> onMain.accept(result));
            } catch (RuntimeException e) {
                TO_MAIN.add(() -> onError.accept(e));
            }
        });
    }

    /** Once per tick, main thread. */
    public static void tick() {
        Runnable r;
        while ((r = TO_MAIN.poll()) != null) r.run();
        if (!VIEWS.isEmpty()) sendDirty();
        if (!JOBS.isEmpty()) writeTick();
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    // =============================================================================================================
    // Show
    // =============================================================================================================

    /** A layer of fake blocks: one edit and who sees it. Main thread. */
    public static final class Layer {
        private BlockEdit edit;
        private final Set<UUID> viewers = new LinkedHashSet<>();
        private boolean gone;

        public BlockEdit edit() {
            return edit;
        }

        public Set<UUID> viewers() {
            return Set.copyOf(viewers);
        }
    }

    private static final List<Layer> LAYERS = new ArrayList<>(); // later layers win where they overlap
    private static final Map<UUID, View> VIEWS = new HashMap<>();

    /** What one viewer sees now: per world, the merged fake sections. Never changed once made (Netty reads it). */
    private record Snapshot(Map<ResourceKey<Level>, Long2ObjectMap<BlockEdit.Section>> worlds) {
        static final Snapshot EMPTY = new Snapshot(Map.of());

        BlockEdit.Section get(ResourceKey<Level> world, long key) {
            Long2ObjectMap<BlockEdit.Section> m = worlds.get(world);
            return m == null ? null : m.get(key);
        }
    }

    private static final class View {
        final UUID player;
        volatile Snapshot snap = Snapshot.EMPTY;
        final Map<ResourceKey<Level>, LongLinkedOpenHashSet> dirty = new HashMap<>();
        Boolean flightBefore; // allow-flight before the first layer, given back after the last
        Hook hook;

        View(UUID player) {
            this.player = player;
        }
    }

    public static Layer show(BlockEdit edit, Collection<UUID> viewers) {
        Layer layer = new Layer();
        layer.edit = edit;
        layer.viewers.addAll(viewers);
        LAYERS.add(layer);
        refresh(layer.viewers);
        return layer;
    }

    /** Swaps the layer's edit; only the sections that really changed are sent again. */
    public static void update(Layer layer, BlockEdit edit) {
        if (layer.gone) return;
        layer.edit = edit;
        refresh(layer.viewers);
    }

    public static void setViewers(Layer layer, Collection<UUID> viewers) {
        if (layer.gone) return;
        Set<UUID> touched = new LinkedHashSet<>(layer.viewers);
        touched.addAll(viewers);
        layer.viewers.clear();
        layer.viewers.addAll(viewers);
        refresh(touched);
    }

    /** The layer goes; its viewers get the real blocks back. */
    public static void hide(Layer layer) {
        if (layer.gone) return;
        layer.gone = true;
        LAYERS.remove(layer);
        refresh(layer.viewers);
    }

    private static void refresh(Collection<UUID> players) {
        for (UUID id : players) {
            View view = VIEWS.computeIfAbsent(id, View::new);
            Snapshot old = view.snap;
            Snapshot now = merge(id);
            // dirty: sections whose fake content differs (content, not object: a rebuilt edit makes new objects)
            for (ResourceKey<Level> w : union(old.worlds.keySet(), now.worlds.keySet())) {
                Long2ObjectMap<BlockEdit.Section> a = old.worlds.getOrDefault(w, Long2ObjectMaps.emptyMap());
                Long2ObjectMap<BlockEdit.Section> b = now.worlds.getOrDefault(w, Long2ObjectMaps.emptyMap());
                LongLinkedOpenHashSet d = view.dirty.computeIfAbsent(w, k -> new LongLinkedOpenHashSet());
                for (long k : a.keySet()) if (!same(a.get(k), b.get(k))) d.add(k);
                for (long k : b.keySet()) if (!a.containsKey(k)) d.add(k);
            }
            view.snap = now; // published before any send, so a chunk sent from now on gets the new blocks
            boolean any = !now.worlds.isEmpty();
            org.bukkit.entity.Player p = Bukkit.getPlayer(id);
            if (any) {
                hook(view);
                if (p != null && view.flightBefore == null) {
                    view.flightBefore = p.getAllowFlight();
                    p.setAllowFlight(true);
                }
            } else if (view.flightBefore != null) {
                if (p != null) p.setAllowFlight(view.flightBefore);
                view.flightBefore = null;
            }
        }
    }

    private static <T> Set<T> union(Set<T> a, Set<T> b) {
        Set<T> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return s;
    }

    private static boolean same(BlockEdit.Section a, BlockEdit.Section b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Arrays.equals(a.palette(), b.palette()) && Arrays.equals(a.cells(), b.cells());
    }

    private static Snapshot merge(UUID viewer) {
        Map<ResourceKey<Level>, Long2ObjectMap<BlockEdit.Section>> worlds = new HashMap<>();
        for (Layer layer : LAYERS) {
            if (!layer.viewers.contains(viewer)) continue;
            Long2ObjectMap<BlockEdit.Section> m = worlds.computeIfAbsent(layer.edit.world, k -> new Long2ObjectOpenHashMap<>());
            for (Long2ObjectMap.Entry<BlockEdit.Section> e : layer.edit.sections.long2ObjectEntrySet()) {
                BlockEdit.Section under = m.get(e.getLongKey());
                m.put(e.getLongKey(), under == null ? e.getValue() : overlay(under, e.getValue()));
            }
        }
        worlds.replaceAll((k, v) -> Long2ObjectMaps.unmodifiable(v));
        worlds.values().removeIf(Map::isEmpty);
        return worlds.isEmpty() ? Snapshot.EMPTY : new Snapshot(Map.copyOf(worlds));
    }

    private static BlockEdit.Section overlay(BlockEdit.Section under, BlockEdit.Section over) {
        int[] states = new int[4096];
        for (int i = 0; i < 4096; i++) {
            int s = over.at(i);
            states[i] = s != -1 ? s : under.at(i);
        }
        return BlockEdit.pack(states, BlockEdit.air());
    }

    /** Sends each viewer's changed sections: fake cells as fake, every other cell as the world has it. */
    private static void sendDirty() {
        for (View view : VIEWS.values()) {
            if (view.dirty.isEmpty()) continue;
            ServerPlayer player = server().getPlayerList().getPlayer(view.player);
            if (player == null) {
                view.dirty.clear(); // the game forgot everything; rejoining gets fresh chunks through the hook
                continue;
            }
            ServerLevel level = player.level();
            LongLinkedOpenHashSet d = view.dirty.get(level.dimension());
            view.dirty.keySet().removeIf(w -> !w.equals(level.dimension())); // other worlds: sent with their chunks
            if (d == null || d.isEmpty()) continue;
            // nearest first
            long[] keys = d.toLongArray();
            int px = player.blockPosition().getX() >> 4, py = player.blockPosition().getY() >> 4, pz = player.blockPosition().getZ() >> 4;
            Long[] boxed = Arrays.stream(keys).boxed().toArray(Long[]::new);
            Arrays.sort(boxed, (a, b) -> Long.compare(dist(a, px, py, pz), dist(b, px, py, pz)));
            int sent = 0;
            List<Packet<?>> packets = new ArrayList<>();
            for (Long k : boxed) {
                if (sent >= showSections) break;
                d.remove(k.longValue());
                int sx = SectionPos.x(k), sy = SectionPos.y(k), sz = SectionPos.z(k);
                if (!level.getChunkSource().chunkMap.getPlayers(new ChunkPos(sx, sz), false).contains(player)) continue;
                LevelChunk chunk = level.getChunkSource().getChunkNow(sx, sz);
                if (chunk == null || sy < level.getMinSectionY() || sy > level.getMaxSectionY()) continue;
                LevelChunkSection real = chunk.getSections()[level.getSectionIndexFromSectionY(sy)];
                BlockEdit.Section fake = view.snap.get(level.dimension(), k);
                Short2ObjectOpenHashMap<BlockState> cells = new Short2ObjectOpenHashMap<>(4096);
                for (int i = 0; i < 4096; i++) {
                    int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                    int f = fake == null ? -1 : fake.at(i);
                    cells.put((short) (x << 8 | z << 4 | y), f != -1 ? Block.stateById(f) : real.getBlockState(x, y, z));
                }
                packets.add(new ClientboundSectionBlocksUpdatePacket(SectionPos.of(sx, sy, sz), cells));
                sent++;
            }
            for (Packet<?> p : packets) player.connection.send(p);
        }
    }

    private static long dist(long key, int x, int y, int z) {
        long dx = SectionPos.x(key) - x, dy = SectionPos.y(key) - y, dz = SectionPos.z(key) - z;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- the pipeline hook ----

    private static final String NAME = "lullaby_blocks";

    private static void hook(View view) {
        ServerPlayer player = server().getPlayerList().getPlayer(view.player);
        if (player == null) return;
        Channel channel = player.connection.connection.channel;
        if (channel == null) return;
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null || pipeline.get(HandlerNames.PACKET_HANDLER) == null) return;
        Hook h = new Hook(view, player);
        try {
            pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, h);
            view.hook = h;
        } catch (NoSuchElementException | IllegalArgumentException ignored) {
            // closed in between
        }
    }

    private static void unhook(View view) {
        ServerPlayer player = server().getPlayerList().getPlayer(view.player);
        if (player == null || player.connection.connection.channel == null) return;
        try {
            player.connection.connection.channel.pipeline().remove(NAME);
        } catch (NoSuchElementException ignored) {
            // not there
        }
    }

    /** Called when a player joins: a viewer gets the hook again (the chunks it gets then carry its fake blocks). */
    public static void joined(UUID player) {
        View view = VIEWS.get(player);
        if (view == null || view.snap.worlds.isEmpty()) return;
        hook(view);
        org.bukkit.entity.Player p = Bukkit.getPlayer(player);
        if (p != null && view.flightBefore != null) p.setAllowFlight(true);
    }

    /**
     * In a viewer's pipeline, outgoing side: after each chunk it adds the fake blocks of that chunk, and it changes
     * the server's own block updates at fake cells into the fake block. Netty's threads: reads only the snapshot.
     */
    private static final class Hook extends ChannelOutboundHandlerAdapter {
        private final View view;
        private final ServerPlayer player;

        Hook(View view, ServerPlayer player) {
            this.view = view;
            this.player = player;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            Snapshot snap = view.snap;
            if (snap.worlds.isEmpty() || !(msg instanceof Packet<?>)) {
                super.write(ctx, msg, promise);
                return;
            }
            ResourceKey<Level> world = player.level().dimension();
            Long2ObjectMap<BlockEdit.Section> fake = snap.worlds.get(world);
            if (fake == null) {
                super.write(ctx, msg, promise);
                return;
            }
            if (msg instanceof ClientboundLevelChunkWithLightPacket chunk) {
                super.write(ctx, msg, promise);
                int minY = player.level().getMinSectionY(), maxY = player.level().getMaxSectionY();
                for (int sy = minY; sy <= maxY; sy++) {
                    BlockEdit.Section s = fake.get(SectionPos.asLong(chunk.x(), sy, chunk.z()));
                    if (s == null) continue;
                    Short2ObjectOpenHashMap<BlockState> cells = new Short2ObjectOpenHashMap<>();
                    for (int i = 0; i < 4096; i++) {
                        int f = s.at(i);
                        if (f == -1) continue;
                        int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                        cells.put((short) (x << 8 | z << 4 | y), Block.stateById(f));
                    }
                    ctx.write(new ClientboundSectionBlocksUpdatePacket(SectionPos.of(chunk.x(), sy, chunk.z()), cells));
                }
                return;
            }
            if (msg instanceof ClientboundBlockUpdatePacket update) {
                BlockPos pos = update.getPos();
                int f = fakeAt(fake, pos);
                super.write(ctx, f == -1 ? msg : new ClientboundBlockUpdatePacket(pos, Block.stateById(f)), promise);
                return;
            }
            if (msg instanceof ClientboundSectionBlocksUpdatePacket update) {
                boolean[] changed = {false};
                Map<Long, Short2ObjectOpenHashMap<BlockState>> out = new HashMap<>();
                update.runUpdates((pos, state) -> {
                    int f = fakeAt(fake, pos);
                    if (f != -1) changed[0] = true;
                    out.computeIfAbsent(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4), k -> new Short2ObjectOpenHashMap<>())
                            .put(SectionPos.sectionRelativePos(pos), f == -1 ? state : Block.stateById(f));
                });
                if (!changed[0]) {
                    super.write(ctx, msg, promise);
                    return;
                }
                // one packet per section; only the last one carries the original promise (a promise completes once)
                List<Map.Entry<Long, Short2ObjectOpenHashMap<BlockState>>> entries = new ArrayList<>(out.entrySet());
                for (int i = 0; i < entries.size(); i++) {
                    long k = entries.get(i).getKey();
                    Packet<?> p = new ClientboundSectionBlocksUpdatePacket(
                            SectionPos.of(SectionPos.x(k), SectionPos.y(k), SectionPos.z(k)), entries.get(i).getValue());
                    if (i == entries.size() - 1) super.write(ctx, p, promise);
                    else ctx.write(p);
                }
                return;
            }
            super.write(ctx, msg, promise);
        }

        private static int fakeAt(Long2ObjectMap<BlockEdit.Section> fake, BlockPos pos) {
            BlockEdit.Section s = fake.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            if (s == null) return -1;
            return s.at((pos.getX() & 15) | (pos.getZ() & 15) << 4 | (pos.getY() & 15) << 8);
        }
    }

    // =============================================================================================================
    // Write
    // =============================================================================================================

    /** A write in progress. {@code done} gets the undo edit (the old blocks) on the main thread. */
    public static final class Job {
        final BlockEdit edit;
        final ServerLevel level;
        final ArrayDeque<Long> chunks = new ArrayDeque<>(); // chunk keys still to write
        final LongOpenHashSet loading = new LongOpenHashSet();
        final LongOpenHashSet ready = new LongOpenHashSet();
        final LongArrayList toLight = new LongArrayList();
        int lighting;
        final BlockEdit.Builder undo;
        final Consumer<BlockEdit> done;
        final Consumer<String> progress;
        final int total;
        long written;
        long lastProgress;
        boolean cancelled;

        Job(BlockEdit edit, ServerLevel level, Consumer<BlockEdit> done, Consumer<String> progress) {
            this.edit = edit;
            this.level = level;
            this.done = done;
            this.progress = progress;
            this.undo = new BlockEdit.Builder(edit.world);
            LongLinkedOpenHashSet set = new LongLinkedOpenHashSet();
            for (long k : edit.sections.keySet()) set.add(ChunkPos.pack(SectionPos.x(k), SectionPos.z(k)));
            for (long c : set) chunks.add(c);
            total = chunks.size();
        }

        public void cancel() {
            cancelled = true;
        }
    }

    private static final List<Job> JOBS = new ArrayList<>();

    /**
     * Writes the edit into its world. {@code progress} gets a short text now and then, {@code done} the undo edit.
     * Returns null (and writes nothing) when the world isn't loaded.
     */
    public static Job write(BlockEdit edit, Consumer<BlockEdit> done, Consumer<String> progress) {
        ServerLevel level = server().getLevel(edit.world);
        if (level == null) return null;
        Job job = new Job(edit, level, done, progress);
        JOBS.add(job);
        return job;
    }

    private static final Set<Heightmap.Types> HEIGHTMAPS = EnumSet.of(Heightmap.Types.WORLD_SURFACE,
            Heightmap.Types.OCEAN_FLOOR, Heightmap.Types.MOTION_BLOCKING, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES);

    private static void writeTick() {
        long end = System.nanoTime() + writeMs * 1_000_000L;
        Job job = JOBS.getFirst();
        if (job.cancelled) {
            finish(job);
            return;
        }
        org.bukkit.World bukkit = job.level.getWorld();
        // keep up to 16 chunks loading in the background
        for (Long c : job.chunks) {
            if (job.loading.size() >= 16) break;
            if (job.loading.contains(c) || job.ready.contains(c)) continue;
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            job.loading.add(c.longValue());
            bukkit.addPluginChunkTicket(cx, cz, plugin);
            bukkit.getChunkAtAsync(cx, cz, true).whenComplete((chunk, error) -> {
                job.loading.remove(c.longValue());
                job.ready.add(c.longValue());
            });
        }
        while (!job.chunks.isEmpty() && System.nanoTime() < end) {
            long c = job.chunks.peekFirst();
            if (!job.ready.contains(c)) break; // the nearest one is still loading: wait for it, keeps the order
            job.chunks.pollFirst();
            job.ready.remove(c);
            LevelChunk chunk = job.level.getChunkSource().getChunkNow(ChunkPos.getX(c), ChunkPos.getZ(c));
            if (chunk == null) {
                bukkit.removePluginChunkTicket(ChunkPos.getX(c), ChunkPos.getZ(c), plugin);
                continue;
            }
            writeChunk(job, chunk);
            job.toLight.add(c);
        }
        if (!job.toLight.isEmpty()) {
            List<ChunkPos> batch = new ArrayList<>();
            for (long c : job.toLight) batch.add(new ChunkPos(ChunkPos.getX(c), ChunkPos.getZ(c)));
            job.toLight.clear();
            job.lighting += batch.size();
            ThreadedLevelLightEngine light = job.level.getChunkSource().getLightEngine();
            light.starlight$serverRelightChunks(batch,
                    pos -> TO_MAIN.add(() -> {
                        resend(job.level, pos);
                        bukkit.removePluginChunkTicket(pos.x(), pos.z(), plugin);
                        job.lighting--;
                        if (job.chunks.isEmpty() && job.lighting == 0) finish(job);
                    }),
                    count -> {
                    });
        }
        if (job.chunks.isEmpty() && job.lighting == 0) {
            finish(job); // nothing (left) to light, e.g. every chunk failed to load
            return;
        }
        long now = System.currentTimeMillis();
        if (now - job.lastProgress > 2000 && !job.chunks.isEmpty()) {
            job.lastProgress = now;
            // FORMAT: write progress
            job.progress.accept("writing: " + (job.total - job.chunks.size()) + " of " + job.total + " chunks, "
                    + job.written + " blocks");
        }
    }

    private static void writeChunk(Job job, LevelChunk chunk) {
        ServerLevel level = job.level;
        int cx = chunk.getPos().x(), cz = chunk.getPos().z();
        ThreadedLevelLightEngine light = level.getChunkSource().getLightEngine();
        for (int sy = level.getMinSectionY(); sy <= level.getMaxSectionY(); sy++) {
            BlockEdit.Section s = job.edit.sections.get(SectionPos.asLong(cx, sy, cz));
            if (s == null) continue;
            LevelChunkSection section = chunk.getSections()[level.getSectionIndexFromSectionY(sy)];
            boolean wasEmpty = section.hasOnlyAir();
            for (int i = 0; i < 4096; i++) {
                int id = s.at(i);
                if (id == -1) continue;
                int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
                BlockState state = Block.stateById(id);
                BlockState old = section.setBlockState(x, y, z, state, false);
                if (old == state) continue;
                job.written++;
                int wx = (cx << 4) | x, wy = (sy << 4) | y, wz = (cz << 4) | z;
                job.undo.put(wx, wy, wz, Block.getId(old));
                if (old.hasBlockEntity() || state.hasBlockEntity()) {
                    BlockPos pos = new BlockPos(wx, wy, wz);
                    if (old.hasBlockEntity()) chunk.removeBlockEntity(pos);
                    if (state.hasBlockEntity()) chunk.getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE);
                }
            }
            if (wasEmpty != section.hasOnlyAir()) light.updateSectionStatus(SectionPos.of(cx, sy, cz), section.hasOnlyAir());
        }
        Heightmap.primeHeightmaps(chunk, HEIGHTMAPS);
        chunk.markUnsaved();
    }

    private static void resend(ServerLevel level, ChunkPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x(), pos.z());
        if (chunk == null) return;
        List<ServerPlayer> players = level.getChunkSource().chunkMap.getPlayers(pos, false);
        if (players.isEmpty()) return;
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(chunk,
                level.getChunkSource().getLightEngine(), null, null);
        for (ServerPlayer p : players) p.connection.send(packet);
    }

    private static void finish(Job job) {
        if (!JOBS.remove(job)) return;
        for (long c : job.chunks) job.level.getWorld().removePluginChunkTicket(ChunkPos.getX(c), ChunkPos.getZ(c), plugin);
        BlockEdit undo = job.undo.build();
        job.done.accept(undo);
    }
}
