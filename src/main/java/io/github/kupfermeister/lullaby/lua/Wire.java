package io.github.kupfermeister.lullaby.lua;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire: Lua values as bytes in LuaJIT's own serialization format (string.buffer's encode/decode, specified in
 * LuaJIT's doc/ext_buffer.html "Serialization Format Specification"). Lua turns a whole table into bytes and back at
 * C speed; Java reads and writes the same bytes here, so a table crosses in ONE call instead of one Lua-Java trip per
 * field (Kupfermeister 2026-11-15).
 *
 * <p>Java's picture of Lua values: null (nil), Boolean, Double (every Lua number), Long (LuaJIT's 64-bit whole
 * numbers, only for values above 2^53), String (UTF-8), {@link Table}. A table tagged with a metatable from the
 * library's wire list (small values like block_pos, objects, text components) carries that list's index in
 * {@link Table#kind}; see wire.lua. Main thread only, like everything that touches Lua (but the class itself holds no
 * Lua state).
 */
public final class Wire {

    private Wire() {
    }

    /** Vanilla's "nothing" (DynamicOps.empty()); sent to Lua as nil. */
    public static final Object NONE = new Object() {
        @Override
        public String toString() {
            return "nothing";
        }
    };
    /** A Lua {} that is both an empty list and an empty map (Lua can't tell them apart). */
    public static final Object EMPTY = new Object() {
        @Override
        public String toString() {
            return "{}";
        }
    };

    /** A Lua table as it arrived: array part t[1..n] (may hold nulls for holes) and hash part. */
    public static final class Table {
        public final List<Object> array = new ArrayList<>();
        public final Map<Object, Object> hash = new LinkedHashMap<>();
        /** Index (0-based) in the library's wire kind list, or -1 for a plain table. */
        public int kind = -1;
    }

    // ---- reading (Lua -> Java) ----

    public static Object read(ByteBuffer bytes) {
        ByteBuffer in = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        Object value = readObject(in, 0);
        if (in.hasRemaining()) throw new IllegalArgumentException("Lullaby bug: extra bytes on the wire");
        return value;
    }

    private static Object readObject(ByteBuffer in, int depth) {
        if (depth > 200) throw new IllegalArgumentException("too deeply nested");
        int tag = readU(in);
        if (tag >= 0x20) {
            int length = tag - 0x20;
            byte[] text = new byte[length];
            in.get(text);
            return new String(text, StandardCharsets.UTF_8);
        }
        return switch (tag) {
            case 0x00 -> null;
            case 0x01 -> Boolean.FALSE;
            case 0x02 -> Boolean.TRUE;
            case 0x06 -> (double) in.getInt();
            case 0x07 -> in.getDouble();
            case 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d -> readTable(in, tag, depth);
            case 0x0e -> {
                int kind = readU(in);
                Object table = readObject(in, depth);
                if (!(table instanceof Table t)) throw new IllegalArgumentException("Lullaby bug: bad tagged table");
                t.kind = kind;
                yield t;
            }
            case 0x10 -> in.getLong();
            case 0x11 -> {
                long value = in.getLong();
                if (value < 0) throw new IllegalArgumentException("number too big (above 2^63)");
                yield value;
            }
            // 0x03..0x05 (light userdata), 0x0f (string dictionary), 0x12 (complex numbers): the library never
            // makes them (no dict option, no ffi for scripts).
            default -> throw new IllegalArgumentException("this kind of value can't be sent to the game");
        };
    }

    private static Table readTable(ByteBuffer in, int tag, int depth) {
        Table t = new Table();
        if (tag == 0x08) return t;
        // Both counts come first, then the entries: tag, [a.U], [h.U], array entries, hash entries.
        // 0x0a/0x0b: array from t[0]; 0x0c/0x0d: from t[1] (a.U still counts slot 0); odd tags have a hash part.
        int arraySize = tag >= 0x0a ? readU(in) : 0;
        int hashSize = (tag & 1) == 1 ? readU(in) : 0;
        if (tag >= 0x0a) {
            if (tag <= 0x0b) {
                Object zero = readObject(in, depth + 1);
                if (zero != null) t.hash.put(0.0, zero);
            }
            for (int i = 1; i < arraySize; i++) t.array.add(readObject(in, depth + 1));
        }
        {
            int size = hashSize;
            for (int i = 0; i < size; i++) {
                Object key = readObject(in, depth + 1);
                Object value = readObject(in, depth + 1);
                t.hash.put(key, value);
            }
        }
        return t;
    }

    private static int readU(ByteBuffer in) {
        int b = in.get() & 0xff;
        if (b < 0xe0) return b;
        if (b != 0xff) return (((b & 0x1f) << 8) | (in.get() & 0xff)) + 0xe0;
        return in.getInt();
    }

    // ---- writing (Java -> Lua) ----

    /** Plain Java values (null, Boolean, Number, String, List, Map) as wire bytes for Lua's buffer.decode. */
    public static ByteBuffer write(Object value) {
        Out out = new Out();
        writeObject(out, value, 0);
        return ByteBuffer.wrap(out.toByteArray());
    }

    private static final class Out extends ByteArrayOutputStream {
        void u(int n) {
            if (n < 0xe0) {
                write(n);
            } else if (n < 0x1fe0) {
                int m = n - 0xe0;
                write(0xe0 | (m >> 8));
                write(m & 0xff);
            } else {
                write(0xff);
                int32(n);
            }
        }

        void int32(int n) {
            for (int i = 0; i < 4; i++) write(n >>> (8 * i));
        }

        void int64(long n) {
            for (int i = 0; i < 8; i++) write((int) (n >>> (8 * i)));
        }
    }

    private static void writeObject(Out out, Object value, int depth) {
        // LuaJIT's buffer.decode refuses more than 100 levels (fixed in LuaJIT); vanilla NBT allows 512.
        // FORMAT: data too deep for Lua
        if (depth >= 100) throw new IllegalArgumentException("the data is nested more than 100 levels deep; Lua can't take it");
        switch (value) {
            case null -> out.write(0x00);
            case Object o when o == NONE -> out.write(0x00);
            case Object o when o == EMPTY -> out.write(0x08);
            case Boolean b -> out.write(b ? 0x02 : 0x01);
            case Long l when l > (1L << 53) || l < -(1L << 53) -> {
                out.write(0x10);
                out.int64(l);
            }
            case Number n -> {
                out.write(0x07);
                out.int64(Double.doubleToRawLongBits(n.doubleValue()));
            }
            case String s -> {
                byte[] text = s.getBytes(StandardCharsets.UTF_8);
                out.u(0x20 + text.length);
                out.write(text, 0, text.length);
            }
            case List<?> list -> {
                if (list.isEmpty()) {
                    out.write(0x08);
                    return;
                }
                out.write(0x0c);
                out.u(list.size() + 1);
                for (Object item : list) writeObject(out, item, depth + 1);
            }
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.write(0x08);
                    return;
                }
                out.write(0x09);
                out.u(map.size());
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    writeObject(out, e.getKey(), depth + 1);
                    writeObject(out, e.getValue(), depth + 1);
                }
            }
            default -> throw new IllegalArgumentException("Lullaby bug: can't send " + value.getClass() + " to Lua");
        }
    }
}
