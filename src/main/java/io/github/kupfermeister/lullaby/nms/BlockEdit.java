package io.github.kupfermeister.lullaby.nms;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One block edit: the block engine's unit of work ({@link BlockEngine} shows it as fake blocks or writes it into the
 * world). Any part of Lullaby can make one (LullaBlend, later {@code fill{}}): a {@link Builder} takes blocks one by
 * one, {@link Builder#build()} packs them per chunk section (16x16x16) into a palette of vanilla state numbers and
 * 4096 palette indices. Index 0 means "untouched": that cell keeps what the world has.
 *
 * <p>A built edit never changes, so any thread may read it. A Builder belongs to the thread that fills it (worker
 * threads build edits; see ARCHITECTURE.md, Threads).
 */
public final class BlockEdit {

    /** One section of an edit. {@code palette[0]} is unused (index 0 = untouched); cells is null when every cell is palette[1]. */
    public record Section(int[] palette, short[] cells) {
        /** The state number at a cell index (x + z*16 + y*256), or -1 when untouched. */
        public int at(int index) {
            int i = cells == null ? 1 : cells[index];
            return i == 0 ? -1 : palette[i];
        }

        int count() {
            if (cells == null) return 4096;
            int n = 0;
            for (short c : cells) if (c != 0) n++;
            return n;
        }
    }

    static final Section ALL_AIR = new Section(new int[]{-1, Block.getId(Blocks.AIR.defaultBlockState())}, null);

    final ResourceKey<Level> world;
    final Long2ObjectMap<Section> sections; // SectionPos.asLong(x, y, z) -> section
    private final long blocks;

    private BlockEdit(ResourceKey<Level> world, Long2ObjectMap<Section> sections, long blocks) {
        this.world = world;
        this.sections = Long2ObjectMaps.unmodifiable(sections);
        this.blocks = blocks;
    }

    public ResourceKey<Level> world() {
        return world;
    }

    /** How many cells this edit changes. */
    public long blocks() {
        return blocks;
    }

    public int sectionCount() {
        return sections.size();
    }

    public static BlockEdit empty(ResourceKey<Level> world) {
        return new BlockEdit(world, new Long2ObjectOpenHashMap<>(), 0);
    }

    // ---- state helpers the builders use (pure: any thread) ----------------------------------------------------

    /** A world key from its id ("minecraft:overworld"). */
    public static ResourceKey<Level> worldKey(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    /** Vanilla's state turned by {@code quarterTurns} clockwise (seen from above), mirrored first when asked. */
    public static int turn(int state, int quarterTurns, boolean mirrorX) {
        BlockState s = Nms.state(state);
        if (mirrorX) s = s.mirror(Mirror.FRONT_BACK);
        s = s.rotate(switch (Math.floorMod(quarterTurns, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        });
        return Block.getId(s);
    }

    public static int air() {
        return ALL_AIR.palette[1];
    }

    // ---- building --------------------------------------------------------------------------------------------

    /** Collects blocks for one edit. One thread only. */
    public static final class Builder {
        private final ResourceKey<Level> world;
        private final Long2ObjectOpenHashMap<int[]> cells = new Long2ObjectOpenHashMap<>(); // -1 = untouched

        public Builder(ResourceKey<Level> world) {
            this.world = world;
        }

        private int[] section(int sx, int sy, int sz) {
            return cells.computeIfAbsent(SectionPos.asLong(sx, sy, sz), k -> {
                int[] a = new int[4096];
                Arrays.fill(a, -1);
                return a;
            });
        }

        /** Sets one cell (a later put wins). */
        public void put(int x, int y, int z, int state) {
            section(x >> 4, y >> 4, z >> 4)[(x & 15) | (z & 15) << 4 | (y & 15) << 8] = state;
        }

        /** Sets every still untouched cell of a box (corners included) to the state. */
        public void fillUntouched(int x0, int y0, int z0, int x1, int y1, int z1, int state) {
            for (int sx = x0 >> 4; sx <= x1 >> 4; sx++)
                for (int sy = y0 >> 4; sy <= y1 >> 4; sy++)
                    for (int sz = z0 >> 4; sz <= z1 >> 4; sz++) {
                        int[] a = section(sx, sy, sz);
                        int ax = Math.max(x0, sx << 4) & 15, bx = Math.min(x1, (sx << 4) + 15) & 15;
                        int ay = Math.max(y0, sy << 4) & 15, by = Math.min(y1, (sy << 4) + 15) & 15;
                        int az = Math.max(z0, sz << 4) & 15, bz = Math.min(z1, (sz << 4) + 15) & 15;
                        for (int y = ay; y <= by; y++)
                            for (int z = az; z <= bz; z++)
                                for (int x = ax; x <= bx; x++) {
                                    int i = x | z << 4 | y << 8;
                                    if (a[i] == -1) a[i] = state;
                                }
                    }
        }

        /** Every chunk column (x, z of chunks) that holds at least one cell so far. */
        public long[] chunkColumns() {
            return cells.keySet().longStream().map(k -> (((long) SectionPos.x(k)) << 32) | (SectionPos.z(k) & 0xffffffffL))
                    .distinct().toArray();
        }

        public BlockEdit build() {
            Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>(cells.size());
            long blocks = 0;
            int air = air();
            for (Long2ObjectMap.Entry<int[]> e : cells.long2ObjectEntrySet()) {
                Section s = pack(e.getValue(), air);
                if (s == null) continue;
                out.put(e.getLongKey(), s);
                blocks += s.count();
            }
            return new BlockEdit(world, out, blocks);
        }
    }

    /** One section's 4096 states (-1 untouched) as palette and indices; null when all untouched. */
    static Section pack(int[] states, int air) {
        int[] palette = new int[8];
        int size = 1;
        palette[0] = -1;
        short[] cells = new short[4096];
        boolean any = false;
        for (int i = 0; i < 4096; i++) {
            int s = states[i];
            if (s == -1) continue;
            any = true;
            int p = 1;
            while (p < size && palette[p] != s) p++;
            if (p == size) {
                if (size == palette.length) palette = Arrays.copyOf(palette, size * 2);
                palette[size++] = s;
            }
            cells[i] = (short) p;
        }
        if (!any) return null;
        palette = Arrays.copyOf(palette, size);
        if (size == 2 && palette[1] == air) {
            boolean full = true;
            for (short c : cells) if (c == 0) { full = false; break; }
            if (full) return ALL_AIR; // whole voided sections share one object
        }
        if (size == 2) {
            boolean full = true;
            for (short c : cells) if (c == 0) { full = false; break; }
            if (full) return new Section(palette, null);
        }
        return new Section(palette, cells);
    }

    // ---- saving (undo files) -----------------------------------------------------------------------------------
    // FORMAT of the file: "LBE1", world id, section count, then per section: key (long), palette size, palette
    // (state TEXT, so a file stays right if vanilla renumbers states), 4096 shorts (or 0 shorts when uniform).

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(0x4C424531);
        out.writeUTF(world.identifier().toString());
        out.writeInt(sections.size());
        for (Long2ObjectMap.Entry<Section> e : sections.long2ObjectEntrySet()) {
            Section s = e.getValue();
            out.writeLong(e.getLongKey());
            out.writeShort(s.palette.length);
            for (int i = 1; i < s.palette.length; i++) out.writeUTF(Nms.blockStateText(s.palette[i]));
            out.writeBoolean(s.cells != null);
            if (s.cells != null) for (short c : s.cells) out.writeShort(c);
        }
    }

    public static BlockEdit read(DataInputStream in) throws IOException {
        if (in.readInt() != 0x4C424531) throw new IOException("not a Lullaby block edit file");
        ResourceKey<Level> world = worldKey(in.readUTF());
        int n = in.readInt();
        Long2ObjectOpenHashMap<Section> map = new Long2ObjectOpenHashMap<>(n);
        long blocks = 0;
        for (int k = 0; k < n; k++) {
            long key = in.readLong();
            int size = in.readShort();
            int[] palette = new int[size];
            palette[0] = -1;
            for (int i = 1; i < size; i++) palette[i] = Nms.blockStateParse(in.readUTF());
            short[] cells = null;
            if (in.readBoolean()) {
                cells = new short[4096];
                for (int i = 0; i < 4096; i++) cells[i] = in.readShort();
            }
            Section s = new Section(palette, cells);
            map.put(key, s);
            blocks += s.count();
        }
        return new BlockEdit(world, map, blocks);
    }
}
