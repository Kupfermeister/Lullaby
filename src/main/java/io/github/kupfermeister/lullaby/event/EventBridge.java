package io.github.kupfermeister.lullaby.event;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.lua.LuaText;
import io.github.kupfermeister.lullaby.lua.ToLua;
import io.github.kupfermeister.lullaby.nms.Fields;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import party.iroiro.luajava.Lua;

/**
 * Connects Paper events to script handlers. The events and their fields are every Paper event, listed in
 * {@code lullaby/events.txt}, which tools/events/EventList.java generates from Paper's own classes at build (that
 * program owns the naming rule and the kinds; the format is in the file's header). This class only reads the list.
 * Main thread only, except the one check in {@link #listen} that keeps other threads away from Lua.
 *
 * <p>Cost: at startup only the list is read (names and method names, no classes). An event's class and methods are
 * looked up the first time a script uses it ({@link #fields}); its fields get numbers then, and keep them until the
 * server stops, so a reload finds them ready. A field read from Lua sends only the event's slot and the field's
 * number: no names as text, no lookups by name. Each registered listener has a number too, so a dispatch sends Lua two
 * whole numbers. A Bukkit listener is registered only for the events, kinds and stages scripts actually use, so
 * without scripts nothing listens.
 *
 * <p>{@code on} handlers run at their stage and also for events another plugin cancelled (they see
 * {@code e.cancelled}); {@code after} handlers run at MONITOR and only for events that were not cancelled.
 *
 * <p>List fields ({@code list:<kind>}) and block snapshots: "a change goes where the value lives". Lua reads a list
 * element by element ({@link #get} copies it, {@link #listItem} hands out one entry) and keeps it for the handler;
 * when the handler ends or first waits, a changed list comes back through {@link #setList} (Paper's setter, or
 * Paper's own list emptied and filled again) and a changed snapshot through {@link #setSnapshot}. A field Paper
 * doesn't let change gives the normal "can't be changed" error then, so no change is ever dropped silently.
 */
public final class EventBridge implements Listener {

    /** What Lullaby makes of a field's Java type: the kind words of events.txt (a word not here is a Lullaby bug). */
    private enum Kind {
        PLAYER, ENTITY, WORLD, BLOCK, CHUNK, LOCATION, VEC3, BLOCK_POS, AABB, BLOCK_STATE, DAMAGE, ITEM_STACK, COMPONENT,
        TEXT, ENUM, KEY, UUID, BOOLEAN, INTEGER, NUMBER, BLOCK_SNAPSHOT, PROFILE, ADDRESS, EFFECT, PLAYED_SOUND, SENDER,
        /** a List, Set or Collection: events.txt writes "list:<element kind>" */
        LIST
    }

    /**
     * One field of one event. {@code read}/{@code write} are null when Lullaby can't handle the type yet; then
     * {@code unread}/{@code unwritten} name it for the error. {@code setter} is null if Paper has none.
     */
    private record Field(String event, String name, MethodHandle getter, Kind read, String unread, MethodHandle setter,
                         Class<?> setType, Kind write, String unwritten, Kind element, Class<?> elementClass) {
    }

    /** One event as events.txt lists it: its class name, whether Paper fires it off the main thread, its field lines. */
    private record Listed(String javaClass, boolean async, List<String[]> fields) {
    }

    /** An event whose class and methods were looked up: its class and "field=number ..." for Lua. */
    private record Resolved(Class<? extends Event> type, String info) {
    }

    private static final Map<String, Listed> LISTED = readList();
    private static final Map<String, Resolved> RESOLVED = new HashMap<>();
    private static final List<Field> FIELDS = new ArrayList<>(); // field number -> field
    private static final Set<String> WARNED_THREAD = ConcurrentHashMap.newKeySet();

    private final Engine engine;
    private final List<String> listeners = new ArrayList<>(); // listener number - 1 -> "Name (kind)" (for errors)
    private ToIntFunction<Object> addSlot;
    /** The list field Lua is reading right now, copied by get (Lua takes its entries at once with listItem). */
    private List<Object> reading = List.of();
    private Kind readingKind;

    public EventBridge(Engine engine) {
        this.engine = engine;
    }

    /** For the library, once at startup: the stages (Bukkit's priorities without MONITOR, in order). */
    public static String info() {
        StringJoiner stages = new StringJoiner(" ");
        for (EventPriority priority : EventPriority.values()) {
            if (priority != EventPriority.MONITOR) stages.add(priority.name().toLowerCase(Locale.ROOT));
        }
        return stages.toString();
    }

    /**
     * Why a script can't listen to this event, or null if it can: not in the list (with close names), or fired off
     * the main thread. The library puts "on: " or "after: " in front.
     */
    public static String refusal(String name) {
        Listed listed = LISTED.get(name);
        // FORMAT: an event Paper fires off the main thread
        if (listed != null && listed.async()) return name + " runs off the main thread (Paper fires it there), and "
                + "scripts only run on the main thread: it can't be used yet";
        if (listed != null || name.equals("reload")) return null;
        List<String> close = Fields.closeNames(name, LISTED.keySet());
        // FORMAT: unknown event, with close names from the list
        return "unknown event \"" + name + "\"" + (close.isEmpty() ? " (the docs site lists every event)"
                : " (did you mean " + String.join(", ", close) + "?)");
    }

    /**
     * The event's fields for Lua as "name=number name=number ..." (empty if it has none). The first call per event
     * looks up its class and methods; a method events.txt names that Paper doesn't have is a Lullaby bug.
     */
    public static String fields(String name) {
        return resolve(name).info();
    }

    /**
     * Registers one Bukkit listener for an event, kind ("on"/"after") and stage (the library checked all three);
     * returns the listener's number, which {@link Engine#dispatch} hands back to Lua with each event.
     */
    public int listen(String name, String kind, String stage) {
        Class<? extends Event> type = resolve(name).type();
        boolean after = kind.equals("after");
        EventPriority priority = after ? EventPriority.MONITOR : EventPriority.valueOf(stage.toUpperCase(Locale.ROOT));
        listeners.add(name + " (" + kind + ")");
        int number = listeners.size();
        Bukkit.getPluginManager().registerEvent(type, this, priority, (listener, e) -> {
            if (!type.isInstance(e)) return; // Bukkit also hands over events of a parent's handler list
            // Lua may only run on the main thread. events.txt leaves out events Paper always fires elsewhere, but some
            // fire on another thread only sometimes: those are skipped (said once in the console), never run.
            if (!Bukkit.isPrimaryThread()) {
                // FORMAT: an event that arrived off the main thread (console, once per event)
                if (WARNED_THREAD.add(name)) engine.plugin().getLogger().warning(name + " came off the main thread: "
                        + "Lullaby skips its script handlers then (scripts only run on the main thread)");
                return;
            }
            engine.dispatch(number, e);
        }, engine.plugin(), after);
        return number;
    }

    /** "PlayerJoinEvent (on)" for a listener number (for internal error messages). */
    public String listenerName(int number) {
        return listeners.get(number - 1);
    }

    /** Removes every listener (before a reload registers them again). */
    public void unregisterAll() {
        HandlerList.unregisterAll(this);
        listeners.clear();
    }

    /** Pushes a field's value for Lua as a kind word plus values (see I.from_java in objects.lua); returns the count. */
    public int get(Lua L, Object event, int number) {
        Field field = FIELDS.get(number);
        // FORMAT: an event field whose type Lullaby can't hand to scripts yet
        if (field.read() == null) throw new IllegalArgumentException("e." + field.name() + " can't be read yet: "
                + "Lullaby has no " + field.unread() + " for scripts yet");
        Object value;
        try {
            value = field.getter().invokeExact(event);
        } catch (Throwable t) {
            throw failure(t);
        }
        if (value == null) return 0;
        if (field.read() == Kind.LIST) {
            // a copy, so Paper changing its list while Lua reads can't mix up the entries
            reading = new ArrayList<>((java.util.Collection<?>) value);
            readingKind = field.element();
            L.push("list");
            L.push(reading.size());
            return 2;
        }
        return push(L, field.read(), value);
    }

    /** Entry {@code i} (1-based) of the list the last {@link #get} copied, as get pushes a value. */
    public int listItem(Lua L, int i) {
        Object value = reading.get(i - 1);
        return value == null ? 0 : push(L, readingKind, value);
    }

    private int push(Lua L, Kind kind, Object value) {
        switch (kind) {
            case ENUM, KEY, UUID -> {
                L.push("v");
                switch (kind) {
                    case ENUM -> L.push(((Enum<?>) value).name().toLowerCase(Locale.ROOT));
                    case KEY -> LuaText.push(L, keyText(value));
                    default -> L.push(value.toString()); // UUID
                }
                return 2;
            }
            default -> {
                // objects, values, components, text, booleans, numbers: the one conversion in ToLua.java
                return ToLua.object(L, value, addSlot);
            }
        }
    }

    /** Sets a field from the Lua value at {@code index}; throws (a red error in Lua) if it can't be set. */
    public void set(Lua L, Object event, int number, int index) {
        Field field = writable(number);
        Class<?> type = field.setType();
        Object value;
        if (L.isNil(index)) {
            if (type.isPrimitive()) throw wrong(field, L, index);
            value = null;
        } else {
            value = switch (field.write()) {
                case BOOLEAN -> {
                    if (!L.isBoolean(index)) throw wrong(field, L, index);
                    yield L.toBoolean(index);
                }
                case INTEGER -> {
                    if (!L.isNumber(index)) throw wrong(field, L, index);
                    yield whole(field, L.toNumber(index), L, index);
                }
                case NUMBER -> {
                    if (!L.isNumber(index)) throw wrong(field, L, index);
                    double d = L.toNumber(index);
                    yield type == float.class || type == Float.class ? (Object) (float) d : (Object) d;
                }
                case TEXT -> {
                    if (!isText(L, index)) throw wrong(field, L, index);
                    yield LuaText.string(L, index);
                }
                // a plain string is literal text, as /tellraw "text"; component tables go through setComponent
                case COMPONENT -> {
                    if (!isText(L, index)) throw wrong(field, L, index);
                    yield Component.text(LuaText.string(L, index));
                }
                case ENUM -> {
                    if (!isText(L, index)) throw wrong(field, L, index);
                    yield constant(field, LuaText.string(L, index));
                }
                default -> throw wrong(field, L, index); // objects and values: setItem, setLocation, setVec3, ...
            };
        }
        invokeSetter(field, event, value);
    }

    /** Sets a text field from a component table (already turned into a component by Codecs). */
    public void setComponent(Object event, int number, Component text) {
        Field field = writable(number);
        if (field.write() != Kind.COMPONENT) throw Fields.wrong("", "e." + field.name(), need(field), "a component");
        invokeSetter(field, event, text);
    }

    /** Sets an item_stack field from an item slot's vanilla stack (a copy goes into the event). */
    public void setItem(Object event, int number, Object vanillaStack) {
        Field field = writable(number);
        if (field.write() != Kind.ITEM_STACK) throw Fields.wrong("", "e." + field.name(), need(field), "an item_stack");
        invokeSetter(field, event, Nms.bukkitItem(vanillaStack));
    }

    /** Sets a location field; {@code worldKey} is a world slot's vanilla key. */
    public void setLocation(Object event, int number, Object worldKey, double x, double y, double z, float yaw,
                            float pitch) {
        Field field = writable(number);
        if (field.write() != Kind.LOCATION) throw Fields.wrong("", "e." + field.name(), need(field), "a location");
        World world = Nms.bukkitWorld(worldKey);
        // FORMAT: a location in a world that isn't loaded
        if (world == null) throw new IllegalArgumentException("e." + field.name() + ": the location's world isn't loaded");
        invokeSetter(field, event, new Location(world, x, y, z, yaw, pitch));
    }

    /** Sets a vec3 field (Bukkit's Vector; a new one goes into the event). */
    public void setVec3(Object event, int number, double x, double y, double z) {
        Field field = writable(number);
        if (field.write() != Kind.VEC3) throw Fields.wrong("", "e." + field.name(), need(field), "a vec3");
        invokeSetter(field, event, new org.bukkit.util.Vector(x, y, z));
    }

    /** Sets an entity field from an entity's or player's slot value; a gone entity or the wrong sort is an error. */
    public void setEntity(Object event, int number, Object slotValue) {
        Field field = writable(number);
        if (field.write() != Kind.ENTITY) throw Fields.wrong("", "e." + field.name(), need(field), "an entity");
        org.bukkit.entity.Entity entity = io.github.kupfermeister.lullaby.nms.Entities.bukkitEntity(slotValue);
        // FORMAT: an entity field set to an entity that is gone (dead, removed, unloaded or offline)
        if (entity == null) throw new IllegalArgumentException("e." + field.name() + ": that entity is gone (dead, "
                + "removed, in an unloaded chunk or offline)");
        // FORMAT: an entity field that takes only some entities (LivingEntity, Projectile, ...)
        if (!field.setType().isInstance(entity)) throw new IllegalArgumentException("e." + field.name() + " must be "
                + need(field) + ", got " + entity.getType().getKey());
        invokeSetter(field, event, entity);
    }

    /** Sets a block_state field (a Bukkit BlockData made from vanilla's state number). */
    public void setBlockState(Object event, int number, int stateId) {
        Field field = writable(number);
        if (field.write() != Kind.BLOCK_STATE) throw Fields.wrong("", "e." + field.name(), need(field), "a block_state");
        invokeSetter(field, event, Nms.bukkitBlockData(stateId));
    }

    /**
     * Puts a changed list back into the event. {@code wire}: the Lua list, each entry a plain value (text, number,
     * true/false) or { kind, parts... }: { "item_stack", slot }, { "entity", slot }, { "block", world slot, x, y, z },
     * { "block_snapshot", world slot, x, y, z, state number }, { "component", table }. Every entry is checked
     * before anything changes. Paper's setter if it has one, else Paper's own list is emptied and filled again.
     */
    @SuppressWarnings("unchecked")
    public void setList(Object event, int number, Object wire, java.util.function.IntFunction<Object> slots) {
        Field field = FIELDS.get(number);
        // FORMAT: a list field that can't be changed (Paper's list is read only, or not meant to be changed)
        if (field.read() == Kind.LIST && field.write() != Kind.LIST) throw new IllegalArgumentException("e."
                + field.name() + " can't be changed (Paper gives it read only; copy it first to work with a changed list)");
        if (field.write() != Kind.LIST) throw Fields.wrong("", "e." + field.name(), need(field), "a list");
        Fields check = new Fields("", null);
        List<Object> entries = check.list(wire, "e." + field.name());
        List<Object> made = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) made.add(entry(field, i + 1, entries.get(i), slots));
        if (field.setter() != null) {
            Object value = java.util.Set.class.isAssignableFrom(field.setType()) ? new java.util.LinkedHashSet<>(made) : made;
            invokeSetter(field, event, value);
            return;
        }
        java.util.Collection<Object> live;
        try {
            Object got = field.getter().invokeExact(event); // invokeExact: the result must be taken as Object first
            live = (java.util.Collection<Object>) got;
        } catch (Throwable t) {
            throw failure(t);
        }
        List<Object> before = new ArrayList<>(live);
        try {
            live.clear();
            live.addAll(made);
        } catch (UnsupportedOperationException | IllegalArgumentException | ClassCastException e) {
            try {
                live.clear();
                live.addAll(before);
            } catch (RuntimeException ignored) {
                // Paper's list takes removals only: what is left of it stays
            }
            // FORMAT: Paper refused the changed list (it only lets entries be removed, or refuses an entry)
            throw new IllegalArgumentException("e." + field.name() + ": Paper refused the change ("
                    + (e.getMessage() != null ? e.getMessage() : "it only takes some changes, for example removing entries") + ")");
        }
    }

    /** One entry of a changed list as the Java thing Paper's list holds; {@code at} is its place for errors. */
    private Object entry(Field field, int at, Object entry, java.util.function.IntFunction<Object> slots) {
        String where = "e." + field.name() + "[" + at + "]";
        Kind kind = field.element();
        String need = switch (kind) {
            case ITEM_STACK -> "an item_stack";
            case ENTITY -> "an entity";
            case PLAYER -> "a player";
            case BLOCK -> "a block";
            case BLOCK_SNAPSHOT -> "a block_snapshot";
            case COMPONENT -> "text or a component";
            case TEXT -> "text";
            case INTEGER -> "a whole number";
            case NUMBER -> "a number";
            default -> "true or false";
        };
        List<Object> parts = entry instanceof io.github.kupfermeister.lullaby.lua.Wire.Table t && t.kind < 0
                && t.hash.isEmpty() && !t.array.isEmpty() && t.array.getFirst() instanceof String ? t.array : null;
        String got = parts != null ? (String) parts.getFirst() : null;
        switch (kind) {
            case TEXT -> {
                if (entry instanceof String text) return text;
            }
            case COMPONENT -> {
                if (entry instanceof String text) return Component.text(text);
                if ("component".equals(got)) return io.github.kupfermeister.lullaby.nms.Codecs.adventure(
                        io.github.kupfermeister.lullaby.nms.Codecs.componentFromTable(parts.get(1), slots));
            }
            case BOOLEAN -> {
                if (entry instanceof Boolean b) return b;
            }
            case INTEGER, NUMBER -> {
                if (entry instanceof Number n) {
                    double d = n.doubleValue();
                    if (kind == Kind.NUMBER) return field.elementClass() == Float.class ? (Object) (float) d : (Object) d;
                    if (d == Math.rint(d) && Math.abs(d) <= Integer.MAX_VALUE) return field.elementClass() == Long.class ? (Object) (long) d : (Object) (int) d;
                }
            }
            case ITEM_STACK -> {
                if ("item_stack".equals(got)) return Nms.bukkitItem(slots.apply(((Number) parts.get(1)).intValue()));
            }
            case ENTITY, PLAYER -> {
                if ("entity".equals(got)) {
                    org.bukkit.entity.Entity e = io.github.kupfermeister.lullaby.nms.Entities.bukkitEntity(
                            slots.apply(((Number) parts.get(1)).intValue()));
                    // FORMAT: a list entry that is an entity that is gone
                    if (e == null) throw new IllegalArgumentException(where + ": that entity is gone (dead, removed, "
                            + "in an unloaded chunk or offline)");
                    // FORMAT: a list entry of the wrong sort of entity (the list holds only Items, ...)
                    if (field.elementClass() != null && !field.elementClass().isInstance(e)) throw new IllegalArgumentException(
                            where + " must be an entity of the sort " + field.elementClass().getSimpleName() + " (Paper's name), got "
                                    + e.getType().getKey());
                    return e;
                }
            }
            case BLOCK, BLOCK_SNAPSHOT -> {
                if (kind.name().toLowerCase(Locale.ROOT).equals(got)) {
                    org.bukkit.block.Block block = block(where, parts, slots);
                    if (kind == Kind.BLOCK) return block;
                    org.bukkit.block.BlockState state = block.getState();
                    state.setBlockData(Nms.bukkitBlockData(((Number) parts.get(5)).intValue()));
                    return state;
                }
            }
            default -> {
            }
        }
        throw Fields.wrong("", where, need, got != null ? (got.equals("?") ? "a " + parts.get(1) : "a " + got)
                : Fields.got(entry));
    }

    private static org.bukkit.block.Block block(String where, List<Object> parts, java.util.function.IntFunction<Object> slots) {
        World world = Nms.bukkitWorld(slots.apply(((Number) parts.get(1)).intValue()));
        // FORMAT: a block in a world that isn't loaded
        if (world == null) throw new IllegalArgumentException(where + ": the block's world isn't loaded");
        return world.getBlockAt(((Number) parts.get(2)).intValue(), ((Number) parts.get(3)).intValue(),
                ((Number) parts.get(4)).intValue());
    }

    /**
     * Puts a changed block snapshot field back: Paper's own snapshot gets the new state (Paper places it after the
     * event). The place can't change: the snapshot belongs to the event's block.
     */
    public void setSnapshot(Object event, int number, Object worldKey, int x, int y, int z, int stateId) {
        Field field = FIELDS.get(number);
        // FORMAT: a block snapshot field whose change Paper doesn't use
        if (field.read() == Kind.BLOCK_SNAPSHOT && field.write() != Kind.BLOCK_SNAPSHOT) throw new IllegalArgumentException(
                "e." + field.name() + " can't be changed (Paper doesn't place it: it only says what was there)");
        if (field.write() != Kind.BLOCK_SNAPSHOT) throw Fields.wrong("", "e." + field.name(), need(field), "a block_snapshot");
        org.bukkit.block.BlockState state;
        try {
            Object got = field.getter().invokeExact(event); // invokeExact: the result must be taken as Object first
            state = (org.bukkit.block.BlockState) got;
        } catch (Throwable t) {
            throw failure(t);
        }
        // FORMAT: a block snapshot field moved to another block
        if (state.getX() != x || state.getY() != y || state.getZ() != z || !Nms.worldIdOf(state.getWorld()).equals(
                Nms.worldIdOf(Nms.bukkitWorld(worldKey)))) throw new IllegalArgumentException("e." + field.name()
                + ".block can't be changed: the snapshot belongs to the event's block");
        state.setBlockData(Nms.bukkitBlockData(stateId));
    }

    /** Gives event fields their item slots (item stacks, show_item hovers); set by JavaFunctions. */
    public void slots(ToIntFunction<Object> addSlot) {
        this.addSlot = addSlot;
    }

    // ---- setting -----------------------------------------------------------------------------------------------

    private static Field writable(int number) {
        Field field = FIELDS.get(number);
        // FORMAT: an event field that can't be written
        if (field.setter() == null) throw new IllegalArgumentException("e." + field.name() + " can't be changed");
        // FORMAT: an event field Paper lets change, but with a type Lullaby can't make yet
        if (field.write() == null) throw new IllegalArgumentException("e." + field.name() + " can't be changed yet: "
                + "Lullaby can't make a " + field.unwritten() + " yet");
        return field;
    }

    private static void invokeSetter(Field field, Object event, Object value) {
        try {
            field.setter().invoke(event, value);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** A Lua number as the setter's whole-number type, checked against that type's range. */
    private static Object whole(Field field, double d, Lua L, int index) {
        Class<?> t = field.setType();
        boolean isLong = t == long.class || t == Long.class, isShort = t == short.class || t == Short.class,
                isByte = t == byte.class || t == Byte.class;
        double min = isLong ? -9007199254740992.0 : isShort ? Short.MIN_VALUE : isByte ? Byte.MIN_VALUE : Integer.MIN_VALUE;
        double max = isLong ? 9007199254740992.0 : isShort ? Short.MAX_VALUE : isByte ? Byte.MAX_VALUE : Integer.MAX_VALUE;
        if (d != Math.rint(d) || d < min || d > max) throw wrong(field, L, index);
        if (isLong) return (long) d;
        if (isShort) return (short) d;
        if (isByte) return (byte) d;
        return (int) d;
    }

    /** "deny" -> Result.DENY for an enum field. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object constant(Field field, String word) {
        try {
            return Enum.valueOf((Class<? extends Enum>) field.setType(), word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw Fields.wrong("", "e." + field.name(), need(field), "\"" + word + "\"");
        }
    }

    /** What a field needs, for "e.x must be <need>, got ...". */
    private static String need(Field field) {
        return switch (field.write()) {
            case BOOLEAN -> "true or false";
            case INTEGER -> "a whole number";
            case NUMBER -> "a number";
            case TEXT -> "text";
            case COMPONENT -> "text or a component";
            case ITEM_STACK -> "an item_stack";
            case LOCATION -> "a location";
            case VEC3 -> "a vec3";
            case BLOCK_STATE -> "a block_state";
            case LIST -> "a list";
            case BLOCK_SNAPSHOT -> "a block_snapshot";
            case ENTITY -> field.setType() == org.bukkit.entity.Entity.class ? "an entity"
                    : "an entity of the sort " + field.setType().getSimpleName() + " (Paper's name)";
            case ENUM -> {
                Object[] all = field.setType().getEnumConstants();
                if (all.length > 16) yield "a " + field.setType().getSimpleName() + " name";
                StringJoiner j = new StringJoiner(", ");
                for (Object k : all) j.add("\"" + ((Enum<?>) k).name().toLowerCase(Locale.ROOT) + "\"");
                yield "one of " + j;
            }
            default -> "left alone";
        };
    }

    private static IllegalArgumentException wrong(Field field, Lua L, int index) {
        return Fields.wrong("", "e." + field.name(), need(field), got(L, index));
    }

    /** Real text only: Lua's own isString is also true for numbers, which would turn 5 into the text "5". */
    private static boolean isText(Lua L, int index) {
        return L.type(index) == Lua.LuaType.STRING;
    }

    /** A Lua value as short text for "got ...", like Fields.got for wire values. */
    private static String got(Lua L, int index) {
        if (L.isNil(index)) return "nil";
        if (L.isBoolean(index)) return String.valueOf(L.toBoolean(index));
        if (L.isNumber(index)) {
            double d = L.toNumber(index);
            return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : Double.toString(d);
        }
        if (L.isString(index)) return "\"" + LuaText.string(L, index) + "\"";
        return "a " + L.type(index).toString().toLowerCase(Locale.ROOT);
    }

    /** A key or keyed thing as its id text: "minecraft:stone". */
    private static String keyText(Object value) {
        if (value instanceof net.kyori.adventure.key.Key key) return key.asString();
        if (value instanceof net.kyori.adventure.key.Keyed keyed) return keyed.key().asString();
        return ((org.bukkit.Keyed) value).getKey().toString();
    }

    /** An exception inside Paper's getter or setter becomes the script's error, with Paper's own message. */
    private static RuntimeException failure(Throwable t) {
        if (t instanceof Error e) throw e;
        return new IllegalArgumentException(t.getMessage() != null ? t.getMessage() : t.toString());
    }

    // ---- the list (read once) and looking up an event (once per event) -------------------------------------------

    private static Map<String, Listed> readList() {
        Map<String, Listed> out = new LinkedHashMap<>();
        String text;
        try (InputStream in = EventBridge.class.getResourceAsStream("/lullaby/events.txt")) {
            if (in == null) throw new IllegalStateException("Lullaby bug: lullaby/events.txt is missing from the jar");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lullaby bug: can't read lullaby/events.txt", e);
        }
        List<String[]> fields = null;
        for (String line : text.split("\n")) {
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("paper-api\t")) continue;
            String[] parts = line.split("\t", -1);
            if (line.startsWith("\t")) {
                fields.add(parts);
            } else {
                fields = new ArrayList<>();
                out.put(parts[0], new Listed(parts[1], parts[3].contains("async"), fields));
            }
        }
        return out;
    }

    private static Resolved resolve(String name) {
        Resolved done = RESOLVED.get(name);
        if (done != null) return done;
        Listed listed = LISTED.get(name);
        if (listed == null) throw new IllegalArgumentException("Lullaby bug: " + name + " is not in events.txt");
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        StringJoiner info = new StringJoiner(" ");
        Class<? extends Event> type;
        try {
            type = Class.forName(listed.javaClass(), false, EventBridge.class.getClassLoader()).asSubclass(Event.class);
            for (String[] f : listed.fields()) {
                // f: "", name, getter, read kind, setter, setter's parameter class, write kind, values
                MethodHandle get = lookup.unreflect(type.getMethod(f[2])).asType(MethodType.methodType(Object.class, Object.class));
                MethodHandle set = null;
                Class<?> setType = null;
                if (!f[4].equals("-")) {
                    setType = parameterClass(f[5]);
                    set = lookup.unreflect(type.getMethod(f[4], setType));
                }
                Kind element = f[3].startsWith("list:") ? kind(f[3].substring(5)) : null;
                Class<?> elementClass = element == null ? null : elementClass(type.getMethod(f[2]).getGenericReturnType());
                FIELDS.add(new Field(name, f[1], get, kind(f[3]), f[3].substring(1), set, setType,
                        f[6].equals("-") ? null : kind(f[6]), f[6].substring(1), element, elementClass));
                info.add(f[1] + "=" + (FIELDS.size() - 1) + (element != null ? ":list" : ""));
            }
        } catch (ReflectiveOperationException | LinkageError e) {
            // FORMAT: events.txt names a class or method this Paper doesn't have (a Lullaby bug)
            throw new IllegalStateException("Lullaby bug: events.txt doesn't match this Paper (" + name + ": " + e
                    + "); rebuild Lullaby so `gradlew events` lists Paper's events again", e);
        }
        Resolved resolved = new Resolved(type, info.toString());
        RESOLVED.put(name, resolved);
        return resolved;
    }

    /** List<Item> -> Item (the same rule as EventList.elementClass); null if Java's type doesn't say. */
    private static Class<?> elementClass(java.lang.reflect.Type generic) {
        if (!(generic instanceof java.lang.reflect.ParameterizedType p) || p.getActualTypeArguments().length != 1) return null;
        java.lang.reflect.Type arg = p.getActualTypeArguments()[0];
        if (arg instanceof java.lang.reflect.WildcardType w) arg = w.getUpperBounds()[0];
        if (arg instanceof java.lang.reflect.ParameterizedType inner) arg = inner.getRawType();
        return arg instanceof Class<?> c ? c : null;
    }

    /** A kind word from events.txt; "?Type" (Lullaby can't handle it yet) gives null; "list:x" gives LIST. */
    private static Kind kind(String word) {
        if (word.startsWith("?")) return null;
        if (word.startsWith("list:")) {
            kind(word.substring(5)); // a Lullaby bug if the element's word is unknown
            return Kind.LIST;
        }
        try {
            return Kind.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            // FORMAT: a kind word the generator wrote that this class doesn't know (a Lullaby bug)
            throw new IllegalStateException("Lullaby bug: events.txt has the kind \"" + word + "\", EventBridge.java doesn't know it");
        }
    }

    private static Class<?> parameterClass(String name) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "int" -> int.class;
            case "long" -> long.class;
            case "short" -> short.class;
            case "byte" -> byte.class;
            case "float" -> float.class;
            case "double" -> double.class;
            case "char" -> char.class;
            default -> Class.forName(name, false, EventBridge.class.getClassLoader());
        };
    }
}
