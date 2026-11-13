package io.github.kupfermeister.lullaby.event;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.lua.LuaText;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import party.iroiro.luajava.Lua;

/**
 * Connects Paper events to script handlers. Events are hand-picked: each one, and each of its fields, is agreed with
 * Kupfermeister ({@link #EVENTS}). Field names come from Paper's own methods by one rule: {@code getPlayer()} -> {@code player},
 * {@code isCancelled()}/{@code setCancelled()} -> {@code cancelled}, {@code joinMessage()} -> {@code join_message}
 * (snake_case); deprecated methods are skipped. Main thread only.
 *
 * <p>A Bukkit listener is registered only for the events, kinds and stages scripts actually use, so without scripts
 * nothing listens. {@code on} handlers run at their stage and also for events another plugin cancelled (they see
 * {@code e.cancelled}); {@code after} handlers run at MONITOR and only for events that were not cancelled.
 */
public final class EventBridge implements Listener {

    private record EventType(Class<? extends Event> type, Set<String> fields) {
    }

    private static final Map<String, EventType> EVENTS = Map.of(
            "PlayerJoinEvent", new EventType(PlayerJoinEvent.class, Set.of("player", "join_message")),
            "PlayerQuitEvent", new EventType(PlayerQuitEvent.class, Set.of("player", "quit_message", "reason")),
            "BlockBreakEvent", new EventType(BlockBreakEvent.class,
                    Set.of("player", "block", "exp_to_drop", "drop_items", "cancelled")));

    private final Engine engine;
    private final Map<String, Method> getters = new HashMap<>(); // "Class|field" -> method (absent: none)
    private final Map<String, Method> setters = new HashMap<>();

    public EventBridge(Engine engine) {
        this.engine = engine;
    }

    public static boolean known(String name) {
        return EVENTS.containsKey(name);
    }

    /** Registers one Bukkit listener for an event, kind ("on"/"after") and stage. */
    public void listen(String name, String kind, String stage) {
        EventType event = EVENTS.get(name);
        boolean after = kind.equals("after");
        EventPriority priority = after ? EventPriority.MONITOR : EventPriority.valueOf(stage.toUpperCase(Locale.ROOT));
        Bukkit.getPluginManager().registerEvent(event.type(), this, priority, (listener, e) -> {
            if (event.type().isInstance(e)) engine.dispatch(name, kind, stage, e);
        }, engine.plugin(), after);
    }

    /** Removes every listener (before a reload registers them again). */
    public void unregisterAll() {
        HandlerList.unregisterAll(this);
    }

    /** Pushes a field's value for Lua as a kind word plus values (see from_java in events.lua); returns the count. */
    public int get(Lua L, Object event, String name, String field) {
        Method getter = accessor(event, name, field, false);
        if (getter == null) return 0;
        Object value = invoke(getter, event);
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
        L.push("v");
        if (value instanceof Component text) {
            LuaText.push(L, MiniMessage.miniMessage().serialize(text)); // text is MiniMessage until text objects exist
        } else if (value instanceof Enum<?> constant) {
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
    public void set(Lua L, Object event, String name, String field, int index) {
        Method setter = accessor(event, name, field, true);
        if (setter == null) throw new IllegalArgumentException("e." + field + " can't be changed");
        Class<?> type = setter.getParameterTypes()[0];
        Object value;
        if (L.isNil(index)) {
            if (type.isPrimitive()) throw new IllegalArgumentException("e." + field + " can't be nil");
            value = null;
        } else if (type == boolean.class || type == Boolean.class) {
            if (!L.isBoolean(index)) throw wrong(field, "true or false");
            value = L.toBoolean(index);
        } else if (type == int.class || type == Integer.class) {
            if (!L.isNumber(index)) throw wrong(field, "a number");
            value = (int) L.toInteger(index);
        } else if (type == double.class || type == Double.class) {
            if (!L.isNumber(index)) throw wrong(field, "a number");
            value = L.toNumber(index);
        } else if (Component.class.isAssignableFrom(type)) {
            if (!L.isString(index)) throw wrong(field, "text");
            value = MiniMessage.miniMessage().deserialize(LuaText.string(L, index));
        } else if (type == String.class) {
            if (!L.isString(index)) throw wrong(field, "text");
            value = LuaText.string(L, index);
        } else {
            throw new IllegalArgumentException("e." + field + " can't be changed");
        }
        invoke(setter, event, value);
    }

    private static IllegalArgumentException wrong(String field, String expected) {
        return new IllegalArgumentException("e." + field + " must be " + expected);
    }

    /** The getter or setter for an agreed field, found once by the naming rule and cached; null if there is none. */
    private Method accessor(Object event, String name, String field, boolean setter) {
        EventType type = EVENTS.get(name);
        if (type == null || !type.fields().contains(field)) return null;
        Map<String, Method> cache = setter ? setters : getters;
        String key = name + "|" + field;
        if (cache.containsKey(key)) return cache.get(key);
        String camel = camel(field);
        String plain = Character.toLowerCase(camel.charAt(0)) + camel.substring(1);
        List<String> names = setter ? List.of("set" + camel, plain) : List.of("get" + camel, "is" + camel, plain);
        Method found = null;
        for (String candidate : names) {
            found = find(type.type(), candidate, setter ? 1 : 0);
            if (found != null) break;
        }
        cache.put(key, found);
        return found;
    }

    private static Method find(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && !method.isAnnotationPresent(Deprecated.class)) {
                return method;
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

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalArgumentException(cause.getMessage() != null ? cause.getMessage() : cause.toString());
        }
    }
}
