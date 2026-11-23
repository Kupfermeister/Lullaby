package io.github.kupfermeister.lullaby.nms;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

/**
 * chunk.data, kept INSIDE each chunk: one entry {@code "lullaby:data"} in the chunk's plugin data (Paper's chunk
 * container, which vanilla's chunk save writes as {@code ChunkBukkitValues} into the region file), holding
 * {@code {DataVersion, data}} exactly like a {@link SavedData} file. So the data travels with the chunk (copied,
 * deleted or regenerated with it), loads only with the chunk, and unused chunks cost nothing. The Lua side is
 * data.lua; this class keeps what Java must know between the calls.
 *
 * <p>A chunk Lua has read is in one of two states:
 * <ul>
 * <li>attached: the chunk is loaded and Lua holds its table. When the chunk unloads (ChunkUnloadEvent, before the
 * server writes the chunk) and at every save of its world, the table is written into the chunk.</li>
 * <li>written: the chunk unloaded; this class remembers what it wrote. Lua keeps the table as long as some script
 * still holds it ("keep alive", agreed with Kupfermeister 2026-11-23). At each save Lua sends those tables again: one
 * that changed is written into the chunk (loaded in the background at an autosave, at once at reload, stop and world
 * unload). When a script reads c.data again, the kept table is reused if the chunk still holds what Lullaby wrote;
 * if something else changed it (a tool), the chunk's version wins and data.lua locks the old table.</li>
 * </ul>
 * A chunk whose data didn't load cleanly is never written over (that would lose what couldn't be read).
 *
 * <p>Only written when it differs from what the chunk holds, so unchanged data never makes the server save a chunk.
 *
 * <p>Threads: main thread only. Background loads go through Paper (getChunkAtAsync), whose answer comes back on the
 * main thread; an answer from before a reload, or overtaken by a newer save, is dropped.
 */
public final class ChunkData {

    private static final String KEY = "lullaby:data";
    private static final NamespacedKey BUKKIT_KEY = NamespacedKey.fromString(KEY);
    private static final CompoundTag NONE = new CompoundTag(); // "the chunk holds no data" in written

    private final SavedData.Reporter reporter;
    private final Runnable firstUse; // turns the chunk unload listener on (Engine)
    private final Map<ResourceKey<Level>, LongSet> attached = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2ObjectMap<CompoundTag>> written = new HashMap<>();
    private final Map<ResourceKey<Level>, LongSet> blocked = new HashMap<>();
    private final Map<ResourceKey<Level>, Long2IntMap> pending = new HashMap<>(); // background write's number
    private int generation; // +1 at every reload: background answers from before are dropped
    private int nextWrite;

    public ChunkData(SavedData.Reporter reporter, Runnable firstUse) {
        this.reporter = reporter;
        this.firstUse = firstUse;
    }

    /** The data's name in errors: the script code that reaches it. */
    public static String name(ResourceKey<Level> world, int x, int z) {
        return "chunk(world(\"" + world.identifier() + "\"), chunk_pos(" + x + ", " + z + ")).data"; // FORMAT
    }

    /** The region file the chunk is saved in, for errors. */
    private static String file(ResourceKey<Level> world, int x, int z) {
        Path region = MinecraftServer.getServer().storageSource.getDimensionPath(world).resolve("region")
                .resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca");
        return Nms.shownPath(region);
    }

    private static LevelChunk chunkNow(ResourceKey<Level> world, int x, int z) {
        ServerLevel level = MinecraftServer.getServer().getLevel(world);
        return level == null ? null : level.getChunkSource().getChunkNow(x, z);
    }

    private static <T> T of(Map<ResourceKey<Level>, T> map, ResourceKey<Level> world, java.util.function.Supplier<T> make) {
        return map.computeIfAbsent(world, w -> make.get());
    }

    private LongSet attachedIn(ResourceKey<Level> world) {
        return of(attached, world, LongOpenHashSet::new);
    }

    private Long2ObjectMap<CompoundTag> writtenIn(ResourceKey<Level> world) {
        return of(written, world, Long2ObjectOpenHashMap::new);
    }

    private LongSet blockedIn(ResourceKey<Level> world) {
        return of(blocked, world, LongOpenHashSet::new);
    }

    private Long2IntMap pendingIn(ResourceKey<Level> world) {
        return of(pending, world, Long2IntOpenHashMap::new);
    }

    /** Whether Lua holds this loaded chunk's table (the unload listener asks for every chunk that unloads). */
    public boolean isAttached(ResourceKey<Level> world, int x, int z) {
        LongSet set = attached.get(world);
        return set != null && set.contains(ChunkPos.pack(x, z));
    }

    /** Whether any chunk of the world is attached or remembered (saves of other worlds skip Lua). */
    public boolean uses(ResourceKey<Level> world) {
        LongSet a = attached.get(world);
        Long2ObjectMap<CompoundTag> w = written.get(world);
        return (a != null && !a.isEmpty()) || (w != null && !w.isEmpty());
    }

    // ---- reading ----

    /**
     * c.data read from the chunk: {false} if the chunk isn't loaded, else {true, wire bytes or null (no data)}. The
     * chunk is attached from now on.
     */
    public Object[] read(ResourceKey<Level> world, int x, int z, ToIntFunction<Object> addSlot) {
        LevelChunk chunk = chunkNow(world, x, z);
        if (chunk == null) return new Object[]{false};
        long pos = ChunkPos.pack(x, z);
        attach(world, pos);
        blockedIn(world).remove(pos);
        Tag tag = chunk.persistentDataContainer.getTag(KEY);
        if (tag == null) return new Object[]{true, null};
        String name = name(world, x, z);
        if (!(tag instanceof CompoundTag root)) {
            // FORMAT: the chunk's data isn't Lullaby's shape at all
            return unreadable(world, x, z, "it must be a compound { }");
        }
        List<String> problems = new ArrayList<>();
        ByteBuffer bytes;
        try {
            bytes = SavedData.decodeRoot(root, name, addSlot, problems);
        } catch (SavedData.Unreadable e) {
            return unreadable(world, x, z, e.getMessage());
        }
        if (!problems.isEmpty()) {
            blockedIn(world).add(pos);
            SavedData.report(reporter, file(world, x, z), "error", "loading " + name, problems);
        }
        return new Object[]{true, bytes};
    }

    private Object[] unreadable(ResourceKey<Level> world, int x, int z, String why) {
        blockedIn(world).add(ChunkPos.pack(x, z));
        String name = name(world, x, z);
        // FORMAT: a chunk's data can't be loaded at all
        reporter.report("error", file(world, x, z), "loading " + name, name + " can't be loaded: " + why + ". It starts"
                + " empty and is not saved into the chunk until /lullaby reload reads it cleanly");
        return new Object[]{true, null};
    }

    /** What data.lua couldn't make again (one text per line, "" if none): then the chunk isn't written over either. */
    public void loaded(ResourceKey<Level> world, int x, int z, String problems) {
        if (problems.isEmpty()) return;
        blockedIn(world).add(ChunkPos.pack(x, z));
        SavedData.report(reporter, file(world, x, z), "error", "loading " + name(world, x, z),
                List.of(problems.split("\n")));
    }

    private void attach(ResourceKey<Level> world, long pos) {
        if (attached.isEmpty()) firstUse.run();
        attachedIn(world).add(pos);
        writtenIn(world).remove(pos);
        pendingIn(world).remove(pos); // a background write still on its way is no longer needed: the table is attached
    }

    /**
     * A script reads c.data while Lua still keeps the table from before the chunk unloaded: 0 the chunk isn't loaded
     * (the kept table is used as it is), 1 the chunk holds what Lullaby wrote (the table is attached again), 2 something
     * else changed the chunk's data (the chunk's version wins: data.lua locks the old table and reads again).
     */
    public int reattach(ResourceKey<Level> world, int x, int z) {
        LevelChunk chunk = chunkNow(world, x, z);
        if (chunk == null) return 0;
        long pos = ChunkPos.pack(x, z);
        boolean ours = pendingIn(world).containsKey(pos); // our own write is still on its way
        CompoundTag last = writtenIn(world).get(pos);
        boolean same = ours || (last != null && last.equals(held(chunk)));
        if (!same) {
            writtenIn(world).remove(pos);
            return 2;
        }
        attach(world, pos);
        return 1;
    }

    private static CompoundTag held(LevelChunk chunk) {
        return chunk.persistentDataContainer.getTag(KEY) instanceof CompoundTag root ? root : NONE;
    }

    // ---- writing ----

    /**
     * An attached chunk unloads (ChunkUnloadEvent, before the server writes it): its table (data.lua's save tree) goes
     * into the chunk, and is remembered as written.
     */
    public void unloaded(ResourceKey<Level> world, int x, int z, CraftPersistentDataContainer container, Object tree,
                         IntFunction<Object> slots, String luaProblems) {
        long pos = ChunkPos.pack(x, z);
        attachedIn(world).remove(pos);
        CompoundTag root = encode(world, x, z, tree, slots, luaProblems);
        if (blockedIn(world).contains(pos)) {
            notSaved(world, x, z);
            Tag now = container.getTag(KEY);
            writtenIn(world).put(pos, now instanceof CompoundTag c ? c : NONE);
            return;
        }
        put(container, root);
        writtenIn(world).put(pos, root == null ? NONE : root);
    }

    /** A save of one world's chunk tables (autosave, /save-all, reload, stop, world unload); LuaRuntime fills it. */
    public Save startSave(ResourceKey<Level> world) {
        return new Save(world);
    }

    /** One save of one world: {@link #chunk} for every table data.lua has, then {@link #finish}. */
    public final class Save {
        private final ResourceKey<Level> world;
        private final LongSet alive = new LongOpenHashSet(); // kept tables some script still holds

        private Save(ResourceKey<Level> world) {
            this.world = world;
        }

        /**
         * One table: its chunk, whether the chunk unloaded ({@code kept}), its save tree and what data.lua left out
         * (one per line). {@code now}: a changed kept table whose chunk is unloaded loads the chunk at once (reload,
         * stop, world unload) instead of in the background (autosave).
         */
        public void chunk(int x, int z, boolean kept, Object tree, String luaProblems, IntFunction<Object> slots,
                          boolean now) {
            long pos = ChunkPos.pack(x, z);
            if (kept) alive.add(pos);
            if (blockedIn(world).contains(pos)) {
                notSaved(world, x, z);
                return;
            }
            Long2ObjectMap<CompoundTag> done = writtenIn(world);
            CompoundTag root = encode(world, x, z, tree, slots, luaProblems);
            CompoundTag norm = root == null ? NONE : root;
            LevelChunk chunk = chunkNow(world, x, z);
            if (!kept) {
                if (chunk != null) {
                    put(chunk.persistentDataContainer, root);
                    return;
                }
                // its unload was missed: treat it as kept (written below and remembered)
                attachedIn(world).remove(pos);
                alive.add(pos);
            } else if (Objects.equals(done.get(pos), norm)) {
                return; // unchanged since it was written
            }
            if (chunk != null) {
                put(chunk.persistentDataContainer, root);
                done.put(pos, norm);
                pendingIn(world).remove(pos);
            } else if (now) {
                ServerLevel level = MinecraftServer.getServer().getLevel(world);
                if (level == null) return;
                put(level.getChunk(x, z).persistentDataContainer, root); // loads it now (vanilla's sync load)
                done.put(pos, norm);
                pendingIn(world).remove(pos);
            } else {
                later(world, x, z, root);
            }
        }

        /** Forgets what was written for tables no script holds any more (their chunk holds it anyway). */
        public void finish() {
            Long2ObjectMap<CompoundTag> done = written.get(world);
            if (done == null) return;
            Long2IntMap waiting = pendingIn(world);
            done.keySet().removeIf(pos -> !alive.contains(pos) && !waiting.containsKey(pos));
        }
    }

    /** Loads the chunk in the background (Paper), then writes the data into it on the main thread. */
    private void later(ResourceKey<Level> world, int x, int z, CompoundTag root) {
        ServerLevel level = MinecraftServer.getServer().getLevel(world);
        if (level == null) return;
        long pos = ChunkPos.pack(x, z);
        int number = ++nextWrite, gen = generation;
        pendingIn(world).put(pos, number);
        level.getWorld().getChunkAtAsync(x, z, false, false, loaded -> {
            Long2IntMap waiting = pending.get(world);
            if (gen != generation || waiting == null || waiting.get(pos) != number) return; // reload, or a newer save
            waiting.remove(pos);
            LevelChunk chunk = chunkNow(world, x, z);
            if (chunk == null) return; // the next save tries again
            put(chunk.persistentDataContainer, root);
            writtenIn(world).put(pos, root == null ? NONE : root);
        });
    }

    private CompoundTag encode(ResourceKey<Level> world, int x, int z, Object tree, IntFunction<Object> slots,
                               String luaProblems) {
        String name = name(world, x, z);
        List<String> problems = new ArrayList<>();
        if (!luaProblems.isEmpty()) problems.addAll(List.of(luaProblems.split("\n")));
        CompoundTag root = SavedData.encodeRoot(tree, name, slots, problems);
        SavedData.report(reporter, file(world, x, z), "error", "saving " + name, problems);
        return root;
    }

    /** Puts the data into the chunk only when it differs (a change makes the server save the chunk). */
    private static void put(CraftPersistentDataContainer container, CompoundTag root) {
        Tag now = container.getTag(KEY);
        if (root == null) {
            if (now != null) container.remove(BUKKIT_KEY);
        } else if (!root.equals(now)) {
            container.put(KEY, root);
        }
    }

    private void notSaved(ResourceKey<Level> world, int x, int z) {
        String name = name(world, x, z);
        // FORMAT: a chunk's data not saved because it didn't load cleanly
        reporter.report("warning", file(world, x, z), "saving " + name, name + " is not saved into the chunk: it did"
                + " not load cleanly (see the errors from loading), and saving would lose what couldn't be read");
    }

    // ---- worlds and reloads ----

    /** A world unloaded: everything about its chunks is forgotten (data.lua locks their tables). */
    public void forget(ResourceKey<Level> world) {
        attached.remove(world);
        written.remove(world);
        blocked.remove(world);
        pending.remove(world);
    }

    /** A reload (after every table was written): the new Lua state starts from the chunks again. */
    public void reset() {
        attached.clear();
        written.clear();
        blocked.clear();
        pending.clear();
        generation++;
    }
}
