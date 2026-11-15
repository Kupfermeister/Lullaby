package io.github.kupfermeister.lullaby.nms;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import io.github.kupfermeister.lullaby.lua.Wire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Vanilla's DynamicOps for Lua data: lets EVERY vanilla codec read and write Lua tables (item stacks, components,
 * text, later particles, effects, ...) without hand-written code per type.
 *
 * <p>The values it works on are the wire's plain Java picture after {@link Codecs#plain}: null is never used inside;
 * {@link #NONE} is vanilla's "nothing"; Double/Long numbers; Boolean; String; List; Map (LinkedHashMap); and
 * {@link #EMPTY_TABLE}, a Lua {} that is both an empty list and an empty map (Lua can't tell them apart).
 *
 * <p>STRICT NUMBERS: vanilla's codecs read whole numbers with Number.intValue(), which silently cuts
 * 2.5 to 2 and wraps 3000000000. Numbers handed to codecs are {@link Strict}, whose intValue/longValue/... refuse such
 * values with an error instead. Floats going to Lua become the shortest number that reads back as the same float
 * (0.1f shows as 0.1, not 0.10000000149011612).
 */
public final class LuaOps implements DynamicOps<Object> {

    public static final LuaOps INSTANCE = new LuaOps();
    public static final Object NONE = Wire.NONE;
    public static final Object EMPTY_TABLE = Wire.EMPTY;

    private LuaOps() {
    }

    /** A Lua number handed to a vanilla codec: refuses to be cut or wrapped into a whole-number field. */
    static final class Strict extends Number {
        private final double d;
        private final long l;
        private final boolean isLong;

        Strict(Number n) {
            isLong = n instanceof Long;
            l = n.longValue();
            d = n.doubleValue();
        }

        private long whole(long min, long max, String kind) {
            long value;
            if (isLong) {
                value = l;
            } else {
                if (d != Math.floor(d) || Double.isInfinite(d)) {
                    throw new IllegalArgumentException(text() + " is not a whole number (" + kind + " field)");
                }
                if (d < -9.223372036854776E18 || d >= 9.223372036854776E18) {
                    throw new IllegalArgumentException(text() + " is too big for a " + kind + " field");
                }
                value = (long) d;
            }
            if (value < min || value > max) {
                // FORMAT: number out of range for a vanilla field
                throw new IllegalArgumentException(text() + " is outside " + min + ".." + max + " (" + kind + " field)");
            }
            return value;
        }

        private String text() {
            return isLong ? Long.toString(l) : Codecs.numberText(d);
        }

        @Override
        public int intValue() {
            return (int) whole(Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
        }

        @Override
        public long longValue() {
            return whole(Long.MIN_VALUE, Long.MAX_VALUE, "long");
        }

        @Override
        public short shortValue() {
            return (short) whole(Short.MIN_VALUE, Short.MAX_VALUE, "short");
        }

        @Override
        public byte byteValue() {
            return (byte) whole(Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
        }

        @Override
        public float floatValue() {
            return isLong ? (float) l : (float) d;
        }

        @Override
        public double doubleValue() {
            return isLong ? (double) l : d;
        }

        @Override
        public String toString() {
            return text();
        }
    }

    @Override
    public Object empty() {
        return NONE;
    }

    @Override
    public Object emptyMap() {
        return EMPTY_TABLE;
    }

    @Override
    public Object emptyList() {
        return EMPTY_TABLE;
    }

    @Override
    public Object createNumeric(Number n) {
        if (n instanceof Strict s) return s.isLong ? (Object) s.l : (Object) s.d;
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return createLong(n.longValue());
        }
        if (n instanceof Float f) return createFloat(f);
        return n.doubleValue();
    }

    @Override
    public Object createFloat(float f) {
        if (Float.isNaN(f) || Float.isInfinite(f)) return (double) f;
        return Double.parseDouble(Float.toString(f)); // shortest text that reads back as the same float
    }

    @Override
    public Object createLong(long l) {
        if (l > (1L << 53) || l < -(1L << 53)) return l; // LuaJIT 64-bit whole number
        return (double) l;
    }

    @Override
    public Object createBoolean(boolean b) {
        return b;
    }

    @Override
    public Object createString(String s) {
        return s;
    }

    @Override
    public DataResult<Number> getNumberValue(Object input) {
        if (input instanceof Double || input instanceof Long) return DataResult.success(new Strict((Number) input));
        return DataResult.error(() -> "not a number: " + Codecs.describe(input));
    }

    @Override
    public DataResult<Boolean> getBooleanValue(Object input) {
        if (input instanceof Boolean b) return DataResult.success(b);
        return DataResult.error(() -> "not true or false: " + Codecs.describe(input));
    }

    @Override
    public DataResult<String> getStringValue(Object input) {
        if (input instanceof String s) return DataResult.success(s);
        return DataResult.error(() -> "not text: " + Codecs.describe(input));
    }

    @Override
    public DataResult<Object> mergeToList(Object list, Object value) {
        if (list != NONE && list != EMPTY_TABLE && !(list instanceof List)) {
            return DataResult.error(() -> "not a list: " + Codecs.describe(list), list);
        }
        List<Object> result = list instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        result.add(value);
        return DataResult.success(result);
    }

    @Override
    public DataResult<Object> mergeToList(Object list, List<Object> values) {
        if (list != NONE && list != EMPTY_TABLE && !(list instanceof List)) {
            return DataResult.error(() -> "not a list: " + Codecs.describe(list), list);
        }
        List<Object> result = list instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
        result.addAll(values);
        return DataResult.success(result.isEmpty() ? EMPTY_TABLE : result);
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, Object key, Object value) {
        if (map != NONE && map != EMPTY_TABLE && !(map instanceof Map)) {
            return DataResult.error(() -> "not a table: " + Codecs.describe(map), map);
        }
        if (!(key instanceof String)) return DataResult.error(() -> "key is not text: " + Codecs.describe(key), map);
        Map<Object, Object> result = map instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        if (value != NONE) result.put(key, value);
        return DataResult.success(result.isEmpty() ? EMPTY_TABLE : result);
    }

    // Whole maps at once (DFU's default merges entry by entry from the prefix, so an empty record would stay NONE
    // = nil instead of {}: unit components like unbreakable={} would vanish).
    @Override
    public DataResult<Object> mergeToMap(Object map, Map<Object, Object> values) {
        return mergeAll(map, values.entrySet().stream().map(e -> Pair.of(e.getKey(), e.getValue())));
    }

    @Override
    public DataResult<Object> mergeToMap(Object map, com.mojang.serialization.MapLike<Object> values) {
        return mergeAll(map, values.entries());
    }

    private DataResult<Object> mergeAll(Object map, Stream<Pair<Object, Object>> entries) {
        if (map != NONE && map != EMPTY_TABLE && !(map instanceof Map)) {
            return DataResult.error(() -> "not a table: " + Codecs.describe(map), map);
        }
        Map<Object, Object> result = map instanceof Map<?, ?> m ? new LinkedHashMap<>(m) : new LinkedHashMap<>();
        List<Object> badKeys = new ArrayList<>();
        entries.forEach(p -> {
            if (!(p.getFirst() instanceof String)) badKeys.add(p.getFirst());
            else if (p.getSecond() != NONE) result.put(p.getFirst(), p.getSecond());
        });
        if (!badKeys.isEmpty()) return DataResult.error(() -> "key is not text: " + Codecs.describe(badKeys.get(0)), map);
        return DataResult.success(result.isEmpty() ? EMPTY_TABLE : result);
    }

    @Override
    public DataResult<Stream<Pair<Object, Object>>> getMapValues(Object input) {
        if (input == EMPTY_TABLE) return DataResult.success(Stream.empty());
        if (input instanceof Map<?, ?> m) {
            return DataResult.success(m.entrySet().stream().map(e -> Pair.of((Object) e.getKey(), (Object) e.getValue())));
        }
        return DataResult.error(() -> "not a table with names: " + Codecs.describe(input));
    }

    @Override
    public DataResult<Stream<Object>> getStream(Object input) {
        if (input == EMPTY_TABLE) return DataResult.success(Stream.empty());
        if (input instanceof List<?> l) return DataResult.success(l.stream().map(o -> (Object) o));
        return DataResult.error(() -> "not a list: " + Codecs.describe(input));
    }

    @Override
    public Object createMap(Stream<Pair<Object, Object>> map) {
        Map<Object, Object> result = new LinkedHashMap<>();
        map.forEach(p -> {
            if (p.getSecond() != NONE) result.put(p.getFirst(), p.getSecond());
        });
        return result.isEmpty() ? EMPTY_TABLE : result;
    }

    @Override
    public Object createList(Stream<Object> input) {
        List<Object> result = input.toList();
        return result.isEmpty() ? EMPTY_TABLE : new ArrayList<>(result);
    }

    @Override
    public Object remove(Object input, String key) {
        if (input instanceof Map<?, ?> m && m.containsKey(key)) {
            Map<Object, Object> result = new LinkedHashMap<>(m);
            result.remove(key);
            return result.isEmpty() ? EMPTY_TABLE : result;
        }
        return input;
    }

    /** Into another format (NBT, JSON). Whole Lua numbers become int (long if too big for int), others double. */
    @Override
    public <U> U convertTo(DynamicOps<U> outOps, Object input) {
        return switch (input) {
            case Double d when d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ->
                    outOps.createInt(d.intValue());
            case Double d when d == Math.floor(d) && Math.abs(d) < 9.2E18 -> outOps.createLong(d.longValue());
            case Double d -> outOps.createDouble(d);
            case Long l -> outOps.createLong(l);
            case Boolean b -> outOps.createBoolean(b);
            case String s -> outOps.createString(s);
            case List<?> l -> convertList(outOps, input);
            case Map<?, ?> m -> convertMap(outOps, input);
            default -> input == EMPTY_TABLE ? outOps.emptyMap() : outOps.empty();
        };
    }

    @Override
    public String toString() {
        return "Lua";
    }
}
