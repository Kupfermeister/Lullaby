package io.github.kupfermeister.lullaby.event;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.lua.LuaText;
import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Fields;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import party.iroiro.luajava.Lua;

/**
 * Connects Paper events to script handlers. Events are hand-picked: each one, and each of its fields, is agreed with
 * Kupfermeister ({@link #EVENTS}, the ONE list: the library reads it at startup through {@link #info}). Field names
 * come from Paper's own methods by one rule: {@code getPlayer()} -> {@code player}, {@code isCancelled()}/
 * {@code setCancelled()} -> {@code cancelled}, {@code joinMessage()} -> {@code join_message} (snake_case);
 * deprecated methods are skipped. Main thread only.
 *
 * <p>Speed (an event can fire many times per tick): every field gets a number once, when this class loads, with its
 * getter and setter found then as method handles. A field read from Lua sends only the event's slot and the field's
 * number: no names as text, no lookups by name. A field the naming rule can't find is a Lullaby bug and stops the
 * plugin from loading. Each registered listener has a number too, so a dispatch sends Lua two whole numbers.
 *
 * <p>A Bukkit listener is registered only for the events, kinds and stages scripts actually use, so without scripts
 * nothing listens. {@code on} handlers run at their stage and also for events another plugin cancelled (they see
 * {@code e.cancelled}); {@code after} handlers run at MONITOR and only for events that were not cancelled.
 */
public final class EventBridge implements Listener {

    /** One agreed field of one event: its getter and setter (null if Paper has none) as method handles. */
    private record Field(String event, String name, MethodHandle getter, MethodHandle setter, Class<?> setType) {
    }

    private record EventType(Class<? extends Event> type, List<String> fields) {
    }

    // The agreed events and fields (order = their numbers). Adding one here is all it takes: Lua, error texts and
    // the startup check follow; the docs page lists them in events.lua's doc block.
    private static final Map<String, EventType> EVENTS = new LinkedHashMap<>();

    static {
        EVENTS.put("PlayerJoinEvent", new EventType(PlayerJoinEvent.class, List.of("player", "join_message")));
        EVENTS.put("PlayerQuitEvent", new EventType(PlayerQuitEvent.class, List.of("player", "quit_message", "reason")));
        EVENTS.put("BlockBreakEvent", new EventType(BlockBreakEvent.class,
                List.of("player", "block", "exp_to_drop", "drop_items", "cancelled")));
        EVENTS.put("PlayerMoveEvent", new EventType(PlayerMoveEvent.class, List.of("player", "from", "to", "cancelled")));
    }

    /** Every field of every event, by its number (see {@link #info}). */
    private static final Field[] FIELDS = resolveFields();

    private final Engine engine;
    private final List<String> listeners = new ArrayList<>(); // listener number - 1 -> "Name|kind|stage" (for errors)
    private ToIntFunction<Object> addSlot;

    public EventBridge(Engine engine) {
        this.engine = engine;
    }

    /**
     * For the library, once at startup: the first line lists the stages (Bukkit's priorities without MONITOR, in
     * order), then one line per event: {@code Name<TAB>field=number field=number ...}.
     */
    public static String info() {
        StringBuilder out = new StringBuilder();
        List<String> stages = new ArrayList<>();
        for (EventPriority priority : EventPriority.values()) {
            if (priority != EventPriority.MONITOR) stages.add(priority.name().toLowerCase(Locale.ROOT));
        }
        out.append(String.join(" ", stages));
        int number = 0;
        for (Map.Entry<String, EventType> event : EVENTS.entrySet()) {
            out.append('\n').append(event.getKey()).append('\t');
            for (String field : event.getValue().fields()) {
                if (!field.equals(event.getValue().fields().getFirst())) out.append(' ');
                out.append(field).append('=').append(number++);
            }
        }
        return out.toString();
    }

    /**
     * Registers one Bukkit listener for an event, kind ("on"/"after") and stage (the library checked all three);
     * returns the listener's number, which {@link Engine#dispatch} hands back to Lua with each event.
     */
    public int listen(String name, String kind, String stage) {
        EventType event = EVENTS.get(name);
        boolean after = kind.equals("after");
        EventPriority priority = after ? EventPriority.MONITOR : EventPriority.valueOf(stage.toUpperCase(Locale.ROOT));
        listeners.add(name + " (" + kind + ")");
        int number = listeners.size();
        Class<? extends Event> type = event.type();
        Bukkit.getPluginManager().registerEvent(type, this, priority, (listener, e) -> {
            if (type.isInstance(e)) engine.dispatch(number, e);
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

    /** Pushes a field's value for Lua as a kind word plus values (see from_java in events.lua); returns the count. */
    public int get(Lua L, Object event, int field) {
        MethodHandle getter = FIELDS[field].getter();
        if (getter == null) return 0;
        Object value;
        try {
            value = getter.invokeExact(event);
        } catch (Throwable t) {
            throw failure(t);
        }
        if (value == null) return 0;
        if (value instanceof Player player) {
            L.push("player");
            L.push(player.getUniqueId().toString());
            return 2;
        }
        if (value instanceof Block block) {
            L.push("block");
            L.push(block.getWorld().getKey().toString());
            L.push((long) block.getX());
            L.push((long) block.getY());
            L.push((long) block.getZ());
            return 5;
        }
        if (value instanceof Location location) {
            World world = location.getWorld();
            if (world == null) return 0;
            L.push("location");
            L.push(world.getKey().toString());
            L.push(location.getX());
            L.push(location.getY());
            L.push(location.getZ());
            L.push(location.getYaw());
            L.push(location.getPitch());
            return 7;
        }
        if (value instanceof Component text) {
            // a component table: wire bytes plus the count of item slots in show_item hovers
            Object[] out = Codecs.componentOut(Codecs.vanilla(text), addSlot);
            L.push("c");
            L.push((ByteBuffer) out[0]);
            L.push((int) out[1]);
            return 3;
        }
        L.push("v");
        if (value instanceof Enum<?> constant) {
            L.push(constant.name().toLowerCase(Locale.ROOT));
        } else if (value instanceof Boolean bool) {
            L.push(bool);
        } else if (value instanceof Number number) {
            L.push(number);
        } else if (value instanceof String text) {
            LuaText.push(L, text);
        } else {
            return 0;
        }
        return 2;
    }

    /** Sets a field from the Lua value at {@code index}; throws (a red error in Lua) if it can't be set. */
    public void set(Lua L, Object event, int number, int index) {
        Field field = FIELDS[number];
        if (field.setter() == null) throw cantChange(field);
        Class<?> type = field.setType();
        Object value;
        if (L.isNil(index)) {
            if (type.isPrimitive()) throw wrong(field, type == boolean.class ? "true or false" : "a number", L, index);
            value = null;
        } else if (type == boolean.class || type == Boolean.class) {
            if (!L.isBoolean(index)) throw wrong(field, "true or false", L, index);
            value = L.toBoolean(index);
        } else if (type == int.class || type == Integer.class) {
            if (!L.isNumber(index)) throw wrong(field, "a whole number", L, index);
            double d = L.toNumber(index);
            if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
                throw wrong(field, "a whole number", L, index);
            }
            value = (int) d;
        } else if (type == double.class || type == Double.class) {
            if (!L.isNumber(index)) throw wrong(field, "a number", L, index);
            value = L.toNumber(index);
        } else if (Component.class.isAssignableFrom(type)) {
            // a plain string is literal text, as /tellraw "text"; component tables go through setComponent
            if (!L.isString(index)) throw wrong(field, "text or a component", L, index);
            value = Component.text(LuaText.string(L, index));
        } else if (type == String.class) {
            if (!L.isString(index)) throw wrong(field, "text", L, index);
            value = LuaText.string(L, index);
        } else {
            throw cantChange(field);
        }
        try {
            field.setter().invoke(event, value);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** Sets a text field from a component table (already turned into a component by Codecs). */
    public void setComponent(Object event, int number, Component text) {
        Field field = FIELDS[number];
        if (field.setter() == null || !Component.class.isAssignableFrom(field.setType())) {
            throw Fields.wrong("", "e." + field.name(), field.setter() == null ? "left alone (it can't be changed)"
                    : "true or false, a number or text", "a component");
        }
        try {
            field.setter().invoke(event, text);
        } catch (Throwable t) {
            throw failure(t);
        }
    }

    /** Gives event text fields their item slots (show_item hovers); set by JavaFunctions. */
    public void slots(ToIntFunction<Object> addSlot) {
        this.addSlot = addSlot;
    }

    private static IllegalArgumentException cantChange(Field field) {
        // FORMAT: an event field that can't be written
        return new IllegalArgumentException("e." + field.name() + " can't be changed");
    }

    private static IllegalArgumentException wrong(Field field, String need, Lua L, int index) {
        return Fields.wrong("", "e." + field.name(), need, got(L, index));
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

    /** An exception inside Paper's getter or setter becomes the script's error, with Paper's own message. */
    private static RuntimeException failure(Throwable t) {
        if (t instanceof Error e) throw e;
        return new IllegalArgumentException(t.getMessage() != null ? t.getMessage() : t.toString());
    }

    // ---- resolved once, when the class loads ----

    private static Field[] resolveFields() {
        List<Field> fields = new ArrayList<>();
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Map.Entry<String, EventType> event : EVENTS.entrySet()) {
            Class<?> type = event.getValue().type();
            for (String name : event.getValue().fields()) {
                String camel = camel(name);
                String plain = Character.toLowerCase(camel.charAt(0)) + camel.substring(1);
                Method getter = first(type, 0, "get" + camel, "is" + camel, plain);
                Method setter = first(type, 1, "set" + camel, plain);
                // FORMAT: an agreed event field that Paper doesn't have (a Lullaby bug, found at startup)
                if (getter == null) throw new IllegalStateException("Lullaby bug: " + event.getKey() + " has no getter for " + name);
                try {
                    MethodHandle get = lookup.unreflect(getter).asType(MethodType.methodType(Object.class, Object.class));
                    MethodHandle set = setter == null ? null : lookup.unreflect(setter);
                    fields.add(new Field(event.getKey(), name, get, set, setter == null ? null : setter.getParameterTypes()[0]));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Lullaby bug: can't reach " + event.getKey() + "." + name, e);
                }
            }
        }
        return fields.toArray(Field[]::new);
    }

    private static Method first(Class<?> type, int parameters, String... names) {
        for (String name : names) {
            for (Method method : type.getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters
                        && !method.isAnnotationPresent(Deprecated.class)) {
                    return method;
                }
            }
        }
        return null;
    }

    /** "join_message" -> "JoinMessage" */
    private static String camel(String field) {
        StringBuilder out = new StringBuilder();
        for (String part : field.split("_")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
