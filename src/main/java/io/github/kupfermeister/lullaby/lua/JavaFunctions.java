package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.ScriptCommands;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
    static void fill(Lua lua, Engine engine, Slots slots) {
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
        add(lua, "event_get", L -> events.get(L, slots.get(slot(L, 1)), string(L, 2), string(L, 3)));
        add(lua, "event_set", L -> {
            events.set(L, slots.get(slot(L, 1)), string(L, 2), string(L, 3), 4);
            return 0;
        });
        // Slots (see Slots.java): objects are created in Lua, Java keeps what each number stands for.
        add(lua, "free_slots", L -> {
            slots.free(string(L, 1));
            return 0;
        });
        add(lua, "player_slot", L -> {
            L.push(slots.add(UUID.fromString(string(L, 1))));
            return 1;
        });
        add(lua, "world_slot", L -> {
            L.push(slots.add(Nms.worldKey(string(L, 1))));
            return 1;
        });

        add(lua, "player_name", L -> pushOrNil(L, Nms.playerName(slots.get(slot(L, 1)))));
        add(lua, "player_online", L -> {
            L.push(Nms.isOnline(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "player_find", L -> {
            UUID uuid = Nms.findPlayer(string(L, 1));
            return pushOrNil(L, uuid == null ? null : uuid.toString());
        });
        add(lua, "world_id", L -> pushOrNil(L, Nms.worldId(string(L, 1))));
        add(lua, "dimension_id", L -> pushOrNil(L, Nms.dimensionId(string(L, 1))));
        // Block states travel as vanilla's state numbers (Block.getId); objects.lua keeps one table per number.
        add(lua, "block_state_parse", L -> {
            L.push(Nms.blockStateParse(string(L, 1)));
            return 1;
        });
        add(lua, "block_state_info", L -> {
            push(L, Nms.blockStateInfo((int) L.toInteger(1)));
            return 1;
        });
        add(lua, "block_state_text", L -> {
            push(L, Nms.blockStateText((int) L.toInteger(1)));
            return 1;
        });
        add(lua, "block_state_with", L -> {
            L.push(Nms.blockStateWith((int) L.toInteger(1), string(L, 2)));
            return 1;
        });
        add(lua, "block_state_at", L -> {
            int id = Nms.blockStateAt(slots.get(slot(L, 1)), (int) L.toInteger(2), (int) L.toInteger(3),
                    (int) L.toInteger(4));
            if (id < 0) return 0;
            L.push(id);
            return 1;
        });

        add(lua, "online_players", L -> {
            push(L, String.join("\n", Nms.onlinePlayers()));
            return 1;
        });
        add(lua, "has_permission", L -> {
            Player player = Bukkit.getPlayer(slots.<UUID>get(slot(L, 1)));
            L.push(player != null && player.hasPermission(string(L, 2)));
            return 1;
        });

        // Script commands (commands.lua builds them, ScriptCommands.java holds them). Lists arrive as text:
        // entries joined by "\n"; a snapshot's suggestions as "id<US>a<US>b<RS>id<US>..." (ASCII 31 and 30).
        add(lua, "cmd_exists", L -> {
            L.push(ScriptCommands.exists(string(L, 1)));
            return 1;
        });
        add(lua, "cmd_node", L -> {
            L.push(engine.commands().addNode((int) L.toInteger(1), L.toBoolean(2), string(L, 3), string(L, 4),
                    L.toNumber(5), L.toNumber(6), L.toBoolean(7), lines(string(L, 8)), L.toBoolean(9),
                    L.toBoolean(10), string(L, 11)));
            return 1;
        });
        add(lua, "cmd_alias", L -> {
            engine.commands().addAlias((int) L.toInteger(1), string(L, 2));
            return 0;
        });
        add(lua, "cmd_register", L -> {
            engine.commands().register((int) L.toInteger(1));
            return 0;
        });
        add(lua, "cmd_refresh", L -> {
            Set<Integer> visible = new HashSet<>();
            for (String id : lines(string(L, 2))) visible.add(Integer.parseInt(id));
            Map<Integer, List<String>> suggestions = new HashMap<>();
            for (String record : string(L, 3).split("\u001e")) {
                if (record.isEmpty()) continue;
                String[] parts = record.split("\u001f", -1);
                suggestions.put(Integer.parseInt(parts[0]), List.of(parts).subList(1, parts.length));
            }
            engine.commands().refresh(UUID.fromString(string(L, 1)), visible, suggestions);
            return 0;
        });
        // A command's check rejected a value: the script's text (MiniMessage) to whoever typed it, in red.
        add(lua, "cmd_reject", L -> {
            engine.commands().reject(MiniMessage.miniMessage().deserialize(string(L, 1)));
            return 0;
        });

        add(lua, "message", L -> {
            L.push(Nms.sendMessage(slots.get(slot(L, 1)), MiniMessage.miniMessage().deserialize(string(L, 2))));
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

    /** "a\nb" -> [a, b]; "" -> []. */
    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }

    private static int slot(Lua L, int index) {
        return (int) L.toInteger(index);
    }

    private static int pushOrNil(Lua L, String text) {
        if (text == null) return 0;
        push(L, text);
        return 1;
    }
}
