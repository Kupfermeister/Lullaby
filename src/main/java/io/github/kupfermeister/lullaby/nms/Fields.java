package io.github.kupfermeister.lullaby.nms;

import io.github.kupfermeister.lullaby.lua.Wire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE place that reads and checks the fields of an action's wire table (particle{}, sound{}, ...), and the one
 * wording of their errors: {@code sound{}: volume must be a number, got "loud"}. Every value from a script is checked
 * exactly once, here, where it becomes a game thing; the Lua library checks only what Java can't see (is it a
 * function, a player object, was { } given). Main thread only, like the wire it reads.
 *
 * <p>One Fields per action call: {@code Fields f = new Fields("sound{}", wire); f.number("volume", 1)}. A missing
 * optional field gives the default the caller names (vanilla's own default, never an invented one).
 */
public final class Fields {

    private static final int VEC3 = Codecs.kindIndex("vec3");

    private final String what;
    private final Wire.Table table;

    public Fields(String what, Object wire) {
        this.what = what;
        this.table = (Wire.Table) wire;
    }

    /**
     * Refuses every key the action doesn't take, so a typo (velocty = ...) is an error instead of doing nothing.
     * {@code known}: the action's field names, from its owner's list.
     */
    public void only(List<String> known) {
        for (Object key : table.hash.keySet()) {
            // FORMAT: an action got a field it doesn't take
            if (!known.contains(key)) throw new IllegalArgumentException(what + ": unknown field "
                    + (key instanceof String s ? s : "[" + Codecs.describe(key) + "]") + " (it takes " + names(known) + ")");
        }
        // FORMAT: an action got list entries ({ 1, 2 } style) it doesn't take
        if (!table.array.isEmpty()) throw new IllegalArgumentException(what + ": unknown field [1] (it takes " + names(known) + ")");
    }

    /** The raw value (null if missing), for fields read by a codec or by other code. */
    public Object raw(String field) {
        return table.hash.get(field);
    }

    public boolean has(String field) {
        return table.hash.get(field) != null;
    }

    /** A field that must be there; {@code need} says what it takes ("a vec3 or a list of vec3"). */
    public Object required(String field, String need) {
        Object value = table.hash.get(field);
        // FORMAT: a required field is missing
        if (value == null) throw new IllegalArgumentException(what + ": " + field + " is missing (" + need + ")");
        return value;
    }

    public double number(String field, double missing) {
        Object value = table.hash.get(field);
        return value == null ? missing : number(value, field);
    }

    public int whole(String field, int missing) {
        Object value = table.hash.get(field);
        if (value == null) return missing;
        double d = number(value, field);
        if (d != Math.rint(d)) throw wrong(field, "a whole number", value);
        try {
            return new LuaOps.Strict((Number) value).intValue();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + field + ": " + e.getMessage());
        }
    }

    /** Any 64-bit whole number (the long64 rule): a whole Lua number or a LuaJIT 64-bit number. */
    public long long64(String field, long missing) {
        Object value = table.hash.get(field);
        if (value == null) return missing;
        double d = number(value, field);
        if (d != Math.rint(d)) throw wrong(field, "a whole number", value);
        try {
            return new LuaOps.Strict((Number) value).longValue();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + ": " + field + ": " + e.getMessage());
        }
    }

    public boolean bool(String field, boolean missing) {
        Object value = table.hash.get(field);
        if (value == null) return missing;
        if (value instanceof Boolean b) return b;
        throw wrong(field, "true or false", value);
    }

    public double[] vec3(String field) {
        return vec3(table.hash.get(field), field);
    }

    /** A vec3 as a packet's three floats; missing: 0, 0, 0. */
    public float[] vec3f(String field) {
        Object value = table.hash.get(field);
        if (value == null) return new float[3];
        double[] v = vec3(value, field);
        return new float[]{(float) v[0], (float) v[1], (float) v[2]};
    }

    /** A vec3 or a list of vec3, as a list (still unchecked entries: read each with {@link #vec3(Object, String)}). */
    public List<Object> vec3s(String field, String need) {
        Object value = required(field, need);
        return value instanceof Wire.Table t && t.kind == VEC3 ? List.of(t) : list(value, field);
    }

    public List<Object> list(String field) {
        return list(table.hash.get(field), field);
    }

    /**
     * One of a fixed set of names; the error lists the names from the owner's own map (never a typed copy).
     * {@code missing}: the value when the field is not there; null makes the field required.
     */
    public <T> T choice(String field, Map<String, T> names, T missing) {
        Object value = table.hash.get(field);
        if (value == null) {
            // FORMAT: a required choice is missing
            if (missing == null) throw new IllegalArgumentException(what + ": " + field + " is missing (" + names(names) + ")");
            return missing;
        }
        T found = value instanceof String s ? names.get(s) : null;
        if (found == null) throw wrong(field, "one of " + names(names), value);
        return found;
    }

    // ---- checks of single values (also for list entries: "pos[3]") ----

    public double number(Object value, String field) {
        if (value instanceof Double d) return d;
        if (value instanceof Long l) return l;
        throw wrong(field, "a number", value);
    }

    public double[] vec3(Object value, String field) {
        if (value instanceof Wire.Table t && t.kind == VEC3 && t.array.size() == 3) {
            return new double[]{number(t.array.get(0), field), number(t.array.get(1), field), number(t.array.get(2), field)};
        }
        throw wrong(field, "a vec3", value);
    }

    /** A list: t[1..n] with no holes and nothing else (LuaJIT may keep part of a list in its hash part). */
    public List<Object> list(Object value, String field) {
        if (value instanceof Wire.Table t && t.kind < 0) {
            if (t.hash.isEmpty()) {
                for (int i = 0; i < t.array.size(); i++) {
                    // FORMAT: a list with a hole
                    if (t.array.get(i) == null) throw new IllegalArgumentException(what + ": " + field + " has a hole at [" + (i + 1) + "]");
                }
                return t.array;
            }
            List<Object> sequence = Codecs.sequence(t);
            if (sequence != null) return sequence;
        }
        throw wrong(field, "a list", value);
    }

    /** The error for a field with a value of the wrong kind. */
    public IllegalArgumentException wrong(String field, String need, Object got) {
        return wrong(what, field, need, got(got));
    }

    // ---- the one wording, also used where values don't come from a wire table (event fields) ----

    /** {@code <what>: <field> must be <need>, got <got>}; {@code what} may be "" (then no prefix). */
    public static IllegalArgumentException wrong(String what, String field, String need, String got) {
        // FORMAT: a value of the wrong kind
        return new IllegalArgumentException((what.isEmpty() ? "" : what + ": ") + field + " must be " + need + ", got " + got);
    }

    /** A wire value as short text for "got ...": "a vec3", "a table", "\"loud\"", "2.5", "nil". */
    public static String got(Object value) {
        if (value instanceof Wire.Table t) return t.kind >= 0 ? Codecs.article(Codecs.kindName(t.kind)) : "a table";
        return Codecs.describe(value == null ? LuaOps.NONE : value);
    }

    /** "\"a\", \"b\" or \"c\"" from a name map, in its order. */
    public static String names(Map<String, ?> names) {
        return names(names.keySet());
    }

    /** "\"a\", \"b\" or \"c\"" from names, in their order. */
    public static String names(java.util.Collection<String> names) {
        List<String> quoted = new ArrayList<>(names.size());
        for (String name : names) quoted.add("\"" + name + "\"");
        if (quoted.size() <= 1) return String.join("", quoted);
        return String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.getLast();
    }

    /** A name map in the order of the given values (for {@link #choice}); names as the owner writes them. */
    @SafeVarargs
    public static <T> Map<String, T> byName(java.util.function.Function<T, String> name, T... values) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T value : values) map.put(name.apply(value), value);
        return map;
    }
}
