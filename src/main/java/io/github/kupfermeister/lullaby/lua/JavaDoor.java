package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Nms;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import party.iroiro.luajava.Lua;

/**
 * The raw Java door (config {@code unlock.java}, script table {@code java}, Lua side in lib/java.lua): scripts load
 * any class by name, create objects, call public methods and read and write public fields. Off by default: then
 * {@link #fill} is never called and nothing of this exists. The library itself never uses the door; library features
 * go through JavaFunctions and {@code nms}.
 *
 * <p>Every function here is added with JavaFunctions.add, so each raw call checks the script time limit. Java
 * objects live in {@link Slots} like every Lullaby object; a reload throws them away with the Lua state. Only plain
 * values cross into Java (nil, true/false, numbers, text, raw objects): never a Lua function, so Java can never call
 * a script back and no other thread can reach Lua through the door. Main thread only.
 *
 * <p>Arguments: Lua passes a whole number "mask" before the values; bit i (from 0) set means value i+1 is the slot of
 * a raw object, else it is a plain Lua value. Results: a kind number first, 0 = a plain value follows (or nothing
 * for nil), 1 = a raw object follows as its slot and its class number.
 */
final class JavaDoor {

    private final Slots slots;
    private final List<Class<?>> classes = new ArrayList<>(); // class number - 1 -> class
    private final Map<Class<?>, Integer> classIds = new HashMap<>();
    private final Map<Class<?>, Map<String, Method[]>> methods = new HashMap<>();   // public methods by name
    private final Map<Class<?>, Map<String, Field>> fields = new HashMap<>();       // public fields by name
    private final Map<Class<?>, Constructor<?>[]> constructors = new HashMap<>();

    private JavaDoor(Slots slots) {
        this.slots = slots;
    }

    /** Pushes a new table holding the door's functions (called by J.java_door, only when unlocked). */
    static void fill(Lua lua, Slots slots) {
        JavaDoor door = new JavaDoor(slots);
        lua.newTable();
        JavaFunctions.add(lua, "class", L -> {
            L.push(door.classId(door.load(LuaText.string(L, 1))));
            return 1;
        });
        // A raw object holding a java.lang.Class -> that class's number (java.class(obj:getClass())).
        JavaFunctions.add(lua, "class_of", L -> {
            if (!(door.object(L, 1) instanceof Class<?> c))
                // FORMAT: java.class( ) given a raw object that isn't a class
                throw new IllegalArgumentException("java.class needs a class name or a java.lang.Class, got "
                        + door.object(L, 1).getClass().getName());
            L.push(door.classId(c));
            return 1;
        });
        JavaFunctions.add(lua, "class_slot", L -> {
            L.push(slots.add(door.cls(L, 1)));
            return 1;
        });
        JavaFunctions.add(lua, "class_name", L -> {
            LuaText.push(L, door.cls(L, 1).getName());
            return 1;
        });
        // "m" a method, "f" a field (arrays: "length"), nothing: neither. Arguments: class number, static?, name.
        JavaFunctions.add(lua, "member", L -> {
            String kind = door.member(door.cls(L, 1), L.toBoolean(2), LuaText.string(L, 3));
            if (kind == null) return 0;
            L.push(kind);
            return 1;
        });
        JavaFunctions.add(lua, "call", L -> door.call(L, door.object(L, 1), null, LuaText.string(L, 2)));
        JavaFunctions.add(lua, "static_call", L -> door.call(L, null, door.cls(L, 1), LuaText.string(L, 2)));
        JavaFunctions.add(lua, "new", L -> door.construct(L, door.cls(L, 1)));
        JavaFunctions.add(lua, "get", L -> door.push(L, door.getField(door.object(L, 1), null, LuaText.string(L, 2))));
        JavaFunctions.add(lua, "static_get", L -> door.push(L, door.getField(null, door.cls(L, 1), LuaText.string(L, 2))));
        JavaFunctions.add(lua, "set", L -> {
            door.setField(L, door.object(L, 1), null, LuaText.string(L, 2));
            return 0;
        });
        JavaFunctions.add(lua, "static_set", L -> {
            door.setField(L, null, door.cls(L, 1), LuaText.string(L, 2));
            return 0;
        });
        JavaFunctions.add(lua, "index", L -> door.push(L, door.arrayGet(door.object(L, 1), L.toNumber(2))));
        JavaFunctions.add(lua, "set_index", L -> {
            door.arraySet(L, door.object(L, 1), L.toNumber(2));
            return 0;
        });
        JavaFunctions.add(lua, "text", L -> {
            LuaText.push(L, String.valueOf(door.object(L, 1)));
            return 1;
        });
        JavaFunctions.add(lua, "same", L -> {
            L.push(door.object(L, 1).equals(door.object(L, 2)));
            return 1;
        });
        JavaFunctions.add(lua, "raw", L -> door.push(L, door.raw(L)));
        JavaFunctions.add(lua, "wrap", L -> door.wrap(L, door.object(L, 1)));
    }

    // ---- classes ----------------------------------------------------------------------------------------------

    /** A class by its full name, from Lullaby's own class loader (Paper, Bukkit, Minecraft) or any plugin's. */
    private Class<?> load(String name) {
        try {
            return Class.forName(name, true, JavaDoor.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            // try the other plugins' class loaders below
        }
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            try {
                return Class.forName(name, true, plugin.getClass().getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                // next plugin
            }
        }
        // FORMAT: java.class( ) with a name no class has
        throw new IllegalArgumentException("java.class: there is no class named " + name
                + " (it needs the full name, like org.bukkit.Bukkit)");
    }

    private int classId(Class<?> c) {
        Integer id = classIds.get(c);
        if (id != null) return id;
        classes.add(c);
        classIds.put(c, classes.size());
        return classes.size();
    }

    private Class<?> cls(Lua L, int index) {
        return classes.get((int) L.toInteger(index) - 1);
    }

    private Object object(Lua L, int index) {
        return slots.get((int) L.toInteger(index));
    }

    private Map<String, Method[]> methods(Class<?> c) {
        return methods.computeIfAbsent(c, k -> {
            Map<String, List<Method>> byName = new LinkedHashMap<>();
            Map<String, Method> seen = new HashMap<>(); // one method per signature (covariant returns add bridges)
            for (Method m : k.getMethods()) {
                if (m.isBridge()) continue;
                Method usable = usable(m);
                if (usable == null) continue;
                String signature = m.getName() + Arrays.toString(m.getParameterTypes());
                if (seen.putIfAbsent(signature, usable) != null) continue;
                byName.computeIfAbsent(m.getName(), n -> new ArrayList<>()).add(usable);
            }
            Map<String, Method[]> result = new HashMap<>();
            byName.forEach((name, list) -> {
                list.sort(Comparator.comparing(Method::toString)); // the same choice every time on a tie
                result.put(name, list.toArray(Method[]::new));
            });
            return result;
        });
    }

    /**
     * A public method of a class that isn't public itself (List.of(...) gives such a class) can't be called directly:
     * the same method from a public class or interface above it can.
     */
    private static Method usable(Method m) {
        if (open(m.getDeclaringClass())) return m;
        ArrayDeque<Class<?>> todo = new ArrayDeque<>();
        todo.add(m.getDeclaringClass());
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (c != m.getDeclaringClass() && open(c)) {
                try {
                    return c.getMethod(m.getName(), m.getParameterTypes());
                } catch (NoSuchMethodException e) {
                    // not here
                }
            }
            if (c.getSuperclass() != null) todo.add(c.getSuperclass());
            todo.addAll(Arrays.asList(c.getInterfaces()));
        }
        return m.trySetAccessible() ? m : null;
    }

    private static boolean open(Class<?> c) {
        return Modifier.isPublic(c.getModifiers()) && c.getModule().isExported(c.getPackageName());
    }

    private Map<String, Field> fields(Class<?> c) {
        return fields.computeIfAbsent(c, k -> {
            Map<String, Field> result = new HashMap<>();
            for (Field f : k.getFields()) {
                if (open(f.getDeclaringClass()) || f.trySetAccessible()) result.putIfAbsent(f.getName(), f);
            }
            return result;
        });
    }

    private String member(Class<?> c, boolean statics, String name) {
        if (c.isArray() && !statics && name.equals("length")) return "f";
        Method[] found = methods(c).get(name);
        if (found != null) {
            for (Method m : found) if (!statics || Modifier.isStatic(m.getModifiers())) return "m";
        }
        Field f = fields(c).get(name);
        if (f != null && (!statics || Modifier.isStatic(f.getModifiers()))) return "f";
        return null;
    }

    // ---- calls ------------------------------------------------------------------------------------------------

    /** Calls a method: on {@code target}, or a static one of {@code statics}. Arguments from stack position 3 on. */
    private int call(Lua L, Object target, Class<?> statics, String name) {
        Class<?> c = target != null ? target.getClass() : statics;
        String what = c.getName() + ":" + name;
        Method[] found = methods(c).get(name);
        // FORMAT: a method the class doesn't have
        if (found == null) throw new IllegalArgumentException(what + ": there is no public method " + name);
        List<Method> candidates = new ArrayList<>();
        for (Method m : found) if (target != null || Modifier.isStatic(m.getModifiers())) candidates.add(m);
        // FORMAT: an instance method called on the class
        if (candidates.isEmpty()) throw new IllegalArgumentException(what + " is not static: call it on an object");
        Object[] args = arguments(L, 3);
        Choice choice = choose(what, candidates.toArray(Executable[]::new), args);
        Method m = (Method) choice.executable;
        try {
            Object result = m.invoke(Modifier.isStatic(m.getModifiers()) ? null : target, choice.args);
            if (m.getReturnType() == void.class) return 0;
            return push(L, result);
        } catch (InvocationTargetException e) {
            throw failed(what, e.getCause());
        } catch (IllegalAccessException e) {
            throw failed(what, e);
        }
    }

    private int construct(Lua L, Class<?> c) {
        String what = c.getName() + ":new";
        Constructor<?>[] found = constructors.computeIfAbsent(c, k -> {
            Constructor<?>[] list = k.getConstructors();
            Arrays.sort(list, Comparator.comparing(Constructor::toString));
            return list;
        });
        // FORMAT: a class without public constructors (or abstract, an interface)
        if (found.length == 0 || Modifier.isAbstract(c.getModifiers()))
            throw new IllegalArgumentException(what + ": " + c.getName() + " can't be created with new");
        Choice choice = choose(what, found, arguments(L, 3));
        try {
            return push(L, ((Constructor<?>) choice.executable).newInstance(choice.args));
        } catch (InvocationTargetException e) {
            throw failed(what, e.getCause());
        } catch (ReflectiveOperationException e) {
            throw failed(what, e);
        }
    }

    /** The values from stack position {@code first} + 1 on; the mask at {@code first} marks raw objects. */
    private Object[] arguments(Lua L, int first) {
        long mask = (long) L.toNumber(first);
        int n = L.getTop() - first;
        Object[] args = new Object[n];
        for (int i = 0; i < n; i++) args[i] = value(L, first + 1 + i, (mask >> i & 1) != 0);
        return args;
    }

    private Object value(Lua L, int index, boolean raw) {
        if (raw) return slots.get((int) L.toInteger(index));
        return switch (L.type(index)) {
            case BOOLEAN -> L.toBoolean(index);
            case NUMBER -> L.toNumber(index);
            case STRING -> LuaText.string(L, index);
            default -> null; // nil (java.lua lets nothing else through)
        };
    }

    private record Choice(Executable executable, Object[] args) {
    }

    /**
     * The best method or constructor for the arguments: each argument scores how well it fits its parameter (an
     * exact type fits best), the highest total wins, a tie goes to the first in a fixed order. Numbers fit whole-number
     * parameters only when whole and in range. Varargs methods take the extra arguments as their array.
     */
    private static Choice choose(String what, Executable[] candidates, Object[] args) {
        Choice best = null;
        int bestScore = -1;
        for (Executable e : candidates) {
            Object[] packed = pack(e, args);
            if (packed == null) continue;
            Class<?>[] types = e.getParameterTypes();
            int score = 0;
            Object[] converted = new Object[types.length];
            for (int i = 0; i < types.length && score >= 0; i++) {
                if (packed[i] instanceof Rest(Object[] rest)) {
                    Class<?> part = types[i].getComponentType();
                    Object array = Array.newInstance(part, rest.length);
                    for (int j = 0; j < rest.length && score >= 0; j++) {
                        int fit = fit(part, rest[j]);
                        if (fit < 0) score = -1;
                        else {
                            score += fit;
                            Array.set(array, j, convert(part, rest[j]));
                        }
                    }
                    converted[i] = array;
                    continue;
                }
                int fit = fit(types[i], packed[i]);
                if (fit < 0) score = -1;
                else {
                    score += fit;
                    converted[i] = convert(types[i], packed[i]);
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = new Choice(e, converted);
            }
        }
        if (best != null) return best;
        StringJoiner takes = new StringJoiner(" or ");
        for (Executable e : candidates) takes.add(parameters(e));
        // FORMAT: no method or constructor takes these arguments
        throw new IllegalArgumentException(what + " can't take " + argumentTypes(args) + "; it takes " + takes);
    }

    /** The arguments in the method's parameter shape (varargs: the rest as an Object[]); null if the count is wrong. */
    private static Object[] pack(Executable e, Object[] args) {
        int n = e.getParameterCount();
        if (!e.isVarArgs()) return args.length == n ? args : null;
        Class<?> array = e.getParameterTypes()[n - 1];
        if (args.length == n && (args[n - 1] == null || array.isInstance(args[n - 1]))) return args; // the array itself
        if (args.length < n - 1) return null;
        Object[] packed = Arrays.copyOf(args, n);
        packed[n - 1] = new Rest(Arrays.copyOfRange(args, n - 1, args.length));
        return packed;
    }

    /** The extra arguments of a varargs call, before they become the method's array. */
    private record Rest(Object[] values) {
    }

    /** How well a value fits a parameter type: -1 not at all, higher is better. */
    private static int fit(Class<?> type, Object value) {
        if (value == null) return type.isPrimitive() ? -1 : 1;
        if (value instanceof Double d && !type.isInstance(value)) {
            Class<?> t = boxed(type);
            if (t == Double.class) return 4;
            if (t == Float.class) return 3;
            boolean whole = d == Math.rint(d);
            if (t == Long.class) return whole && d >= -0x1p63 && d < 0x1p63 ? 3 : -1;
            if (t == Integer.class) return whole && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? 3 : -1;
            if (t == Short.class) return whole && d >= Short.MIN_VALUE && d <= Short.MAX_VALUE ? 2 : -1;
            if (t == Byte.class) return whole && d >= Byte.MIN_VALUE && d <= Byte.MAX_VALUE ? 2 : -1;
            return -1;
        }
        if (value instanceof String s && !type.isInstance(value)) {
            Class<?> t = boxed(type);
            return t == Character.class && s.length() == 1 ? 1 : -1;
        }
        Class<?> t = boxed(type);
        if (t == value.getClass()) return 4;
        return t.isInstance(value) ? 2 : -1;
    }

    private static Object convert(Class<?> type, Object value) {
        if (value instanceof Double d) {
            Class<?> t = boxed(type);
            if (t == Float.class) return d.floatValue();
            if (t == Long.class) return d.longValue();
            if (t == Integer.class) return d.intValue();
            if (t == Short.class) return d.shortValue();
            if (t == Byte.class) return d.byteValue();
        }
        if (value instanceof String s && boxed(type) == Character.class) return s.charAt(0);
        return value;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == short.class) return Short.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    /** "(int, String)" */
    private static String parameters(Executable e) {
        StringJoiner j = new StringJoiner(", ", "(", ")");
        for (Class<?> t : e.getParameterTypes()) j.add(t.getSimpleName());
        return j.toString();
    }

    /** The arguments as Lua sees them: "(number, string, org.bukkit.World)" */
    private static String argumentTypes(Object[] args) {
        StringJoiner j = new StringJoiner(", ", "(", ")");
        for (Object a : args) {
            j.add(a == null ? "nil" : a instanceof Double ? "number" : a instanceof String ? "string"
                    : a instanceof Boolean ? "boolean" : a.getClass().getName());
        }
        return j.toString();
    }

    /**
     * A Java exception as the script's error: what was called, the exception's short name and its message, no stack
     * trace (the red error names the script's file and line).
     */
    private static IllegalArgumentException failed(String what, Throwable cause) {
        String message = cause.getMessage();
        // FORMAT: a Java exception from a raw call
        return new IllegalArgumentException(what + " failed: " + cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message));
    }

    // ---- fields and arrays ------------------------------------------------------------------------------------

    private Field field(Object target, Class<?> statics, String name) {
        Class<?> c = target != null ? target.getClass() : statics;
        Field f = fields(c).get(name);
        // FORMAT: a field the class doesn't have
        if (f == null) throw new IllegalArgumentException(c.getName() + " has no public field " + name);
        // FORMAT: an instance field read on the class
        if (target == null && !Modifier.isStatic(f.getModifiers()))
            throw new IllegalArgumentException(c.getName() + "." + name + " is not static: read it on an object");
        return f;
    }

    private Object getField(Object target, Class<?> statics, String name) {
        if (target != null && target.getClass().isArray() && name.equals("length")) return Array.getLength(target);
        try {
            return field(target, statics, name).get(target);
        } catch (IllegalAccessException e) {
            throw failed(name, e);
        }
    }

    /** The new value is at stack position 4, its mask at 3. */
    private void setField(Lua L, Object target, Class<?> statics, String name) {
        Field f = field(target, statics, name);
        String what = f.getDeclaringClass().getName() + "." + name;
        // FORMAT: a final field set
        if (Modifier.isFinal(f.getModifiers())) throw new IllegalArgumentException(what + " is final and can't be changed");
        Object value = arguments(L, 3)[0];
        if (fit(f.getType(), value) < 0)
            // FORMAT: a field set to a value of the wrong type
            throw new IllegalArgumentException(what + " needs " + f.getType().getSimpleName() + ", got "
                    + argumentTypes(new Object[] {value}));
        try {
            f.set(target, convert(f.getType(), value));
        } catch (IllegalAccessException e) {
            throw failed(what, e);
        }
    }

    /** Arrays count from 1, like Lua tables: a[1] is Java's a[0]. */
    private static int arrayIndex(Object array, double index) {
        // FORMAT: [ ] used on a raw object that is not an array
        if (!array.getClass().isArray()) throw new IllegalArgumentException(array.getClass().getName()
                + " is not an array: [ ] works only on arrays (use its methods)");
        int length = Array.getLength(array);
        if (index != Math.rint(index) || index < 1 || index > length)
            // FORMAT: an array index outside the array
            throw new IllegalArgumentException("array index must be a whole number from 1 to " + length + ", got "
                    + (index == Math.rint(index) && Math.abs(index) < 1e15 ? Long.toString((long) index) : Double.toString(index)));
        return (int) index - 1;
    }

    private Object arrayGet(Object array, double index) {
        return Array.get(array, arrayIndex(array, index));
    }

    /** The new value is at stack position 4, its mask at 3. */
    private void arraySet(Lua L, Object array, double index) {
        int i = arrayIndex(array, index);
        Object value = arguments(L, 3)[0];
        Class<?> part = array.getClass().getComponentType();
        if (fit(part, value) < 0)
            // FORMAT: an array element set to a value of the wrong type
            throw new IllegalArgumentException("this array holds " + part.getSimpleName() + ", got "
                    + argumentTypes(new Object[] {value}));
        Array.set(array, i, convert(part, value));
    }

    // ---- results ----------------------------------------------------------------------------------------------

    /** A Java value to Lua: nil, true/false, numbers and text as Lua values, everything else as a raw object. */
    private int push(Lua L, Object value) {
        switch (value) {
            case null -> {
                L.push(0);
                return 1;
            }
            case Boolean b -> {
                L.push(0);
                L.push(b);
                return 2;
            }
            case Double d -> {
                L.push(0);
                L.push(d);
                return 2;
            }
            case Float f -> {
                L.push(0);
                L.push((Number) (double) f);
                return 2;
            }
            case Long n -> {
                L.push(0);
                L.push((Number) (double) n); // beyond 2^53 the last digits are lost: Lua numbers are doubles
                return 2;
            }
            case Integer n -> {
                L.push(0);
                L.push(n);
                return 2;
            }
            case Short n -> {
                L.push(0);
                L.push((int) n);
                return 2;
            }
            case Byte n -> {
                L.push(0);
                L.push((int) n);
                return 2;
            }
            case String s -> {
                L.push(0);
                LuaText.push(L, s);
                return 2;
            }
            case Character c -> {
                L.push(0);
                LuaText.push(L, c.toString());
                return 2;
            }
            default -> {
                L.push(1);
                L.push(slots.add(value));
                L.push(classId(value.getClass()));
                return 3;
            }
        }
    }

    // ---- Lullaby objects <-> Java objects ---------------------------------------------------------------------

    /**
     * The Java object behind a Lullaby object. Arguments: kind, slot, then for a block x, y, z (its slot is its
     * world's). A player is the online Bukkit Player, else an OfflinePlayer; a world or block nil if the world is not
     * loaded; an item stack a Bukkit copy; an event the Paper event itself.
     */
    private Object raw(Lua L) {
        String kind = LuaText.string(L, 1);
        Object inside = slots.get((int) L.toInteger(2));
        return switch (kind) {
            case "player" -> {
                UUID uuid = (UUID) inside;
                Object online = Bukkit.getPlayer(uuid);
                yield online != null ? online : Bukkit.getOfflinePlayer(uuid);
            }
            case "world" -> Nms.bukkitWorld(inside);
            case "block" -> {
                World world = Nms.bukkitWorld(inside);
                yield world == null ? null
                        : world.getBlockAt((int) L.toInteger(3), (int) L.toInteger(4), (int) L.toInteger(5));
            }
            case "item_stack" -> Nms.bukkitItem(inside);
            case "event" -> inside;
            default -> throw new IllegalStateException("Lullaby bug: java.raw got kind " + kind);
        };
    }

    /**
     * A Java object as a Lullaby object, where Lullaby has one: pushes a kind word and its values (java.lua makes the
     * object), nothing if there is none.
     */
    private int wrap(Lua L, Object o) {
        switch (o) {
            case OfflinePlayer p -> {
                L.push("player");
                L.push(p.getUniqueId().toString());
                return 2;
            }
            case World w -> {
                L.push("world");
                L.push(Nms.worldIdOf(w));
                return 2;
            }
            case Block b -> {
                L.push("block");
                L.push(Nms.worldIdOf(b.getWorld()));
                L.push(b.getX());
                L.push(b.getY());
                L.push(b.getZ());
                return 5;
            }
            case Location l when l.getWorld() != null -> {
                L.push("location");
                L.push(Nms.worldIdOf(l.getWorld()));
                L.push((Number) l.getX());
                L.push((Number) l.getY());
                L.push((Number) l.getZ());
                L.push((Number) (double) l.getYaw());
                L.push((Number) (double) l.getPitch());
                return 7;
            }
            default -> {
                Object item = Nms.vanillaItem(o);
                if (item == null) return 0;
                L.push("item_stack");
                L.push(slots.add(item));
                return 2;
            }
        }
    }
}
