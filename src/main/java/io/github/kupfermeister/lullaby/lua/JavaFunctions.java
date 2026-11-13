package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.UUID;
import net.kyori.adventure.text.minimessage.MiniMessage;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The raw Java functions the Lua library calls (the table J in the library files). Scripts never see them: the
 * library checks arguments in Lua and calls these with plain values (one Lua-Java trip per library call).
 *
 * <p>Conventions: arguments are read from stack positions 1, 2, ...; a function pushes its results and returns how
 * many; an error is raised in Lua by pushing the message and returning -1 (LuaJava's convention). Java exceptions
 * are turned into such errors by {@link #add}. Text goes through {@link LuaText} (real UTF-8).
 */
final class JavaFunctions {

    private JavaFunctions() {
    }

    /** Fills the table on top of the stack with every function. */
    static void fill(Lua lua, Engine engine) {
        EventBridge events = engine.events();

        add(lua, "log", L -> {
            engine.plugin().getComponentLogger().info(string(L, 1));
            return 0;
        });
        add(lua, "report", L -> {
            engine.errors().report(string(L, 1), string(L, 2), (int) L.toInteger(3), string(L, 4), string(L, 5),
                    string(L, 6));
            return 0;
        });
        add(lua, "set_error_window", L -> {
            engine.errors().setWindowSeconds(L.toNumber(1));
            return 0;
        });
        add(lua, "read_config", L -> {
            String config = engine.readConfig();
            if (config == null) return 0;
            push(L, config);
            return 1;
        });
        add(lua, "list_scripts", L -> {
            push(L, String.join("\n", engine.listScripts()));
            return 1;
        });
        add(lua, "read_script", L -> {
            push(L, engine.readScript(string(L, 1)));
            return 1;
        });

        add(lua, "event_known", L -> {
            L.push(EventBridge.known(string(L, 1)));
            return 1;
        });
        add(lua, "listen", L -> {
            events.listen(string(L, 1), string(L, 2), string(L, 3));
            return 0;
        });
        add(lua, "event_get", L -> events.get(L, L.toJavaObject(1), string(L, 2), string(L, 3)));
        add(lua, "event_set", L -> {
            events.set(L, L.toJavaObject(1), string(L, 2), string(L, 3), 4);
            return 0;
        });

        add(lua, "player_name", L -> pushOrNil(L, Nms.playerName(UUID.fromString(string(L, 1)))));
        add(lua, "player_online", L -> {
            L.push(Nms.isOnline(UUID.fromString(string(L, 1))));
            return 1;
        });
        add(lua, "player_find", L -> {
            UUID uuid = Nms.findPlayer(string(L, 1));
            return pushOrNil(L, uuid == null ? null : uuid.toString());
        });
        add(lua, "material_id", L -> pushOrNil(L, Nms.materialId(string(L, 1))));
        add(lua, "world_id", L -> pushOrNil(L, Nms.worldId(string(L, 1))));
        add(lua, "block_type", L -> pushOrNil(L,
                Nms.blockType(string(L, 1), (int) L.toInteger(2), (int) L.toInteger(3), (int) L.toInteger(4))));

        add(lua, "message", L -> {
            L.push(Nms.sendMessage(UUID.fromString(string(L, 1)), MiniMessage.miniMessage().deserialize(string(L, 2))));
            return 1;
        });
    }

    private static void add(Lua lua, String name, JFunction function) {
        lua.push((JFunction) L -> {
            try {
                return function.__call(L);
            } catch (RuntimeException e) {
                L.push(e.getMessage() != null ? e.getMessage() : e.toString());
                return -1;
            }
        });
        lua.setField(-2, name);
    }

    private static int pushOrNil(Lua L, String text) {
        if (text == null) return 0;
        push(L, text);
        return 1;
    }
}
