package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
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
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The J functions of commands.lua: script commands (commands.lua builds them, nms/ScriptCommands.java holds them).
 * Lists arrive as text: entries joined by "\n"; a snapshot's suggestions as "id<US>a<US>b<RS>id<US>..." (ASCII 31 and
 * 30).
 */
final class CommandFunctions {

    private CommandFunctions() {
    }

    static void fill(Lua lua, Engine engine, Slots slots) {
        add(lua, "online_players", L -> {
            push(L, String.join("\n", Nms.onlinePlayers()));
            return 1;
        });
        add(lua, "has_permission", L -> {
            Player player = Bukkit.getPlayer(slots.<UUID>get(slot(L, 1)));
            L.push(player != null && player.hasPermission(string(L, 2)));
            return 1;
        });
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
        // A command's check rejected a value: the script's text (literal, as /tellraw "text") to whoever typed it, in red.
        add(lua, "cmd_reject", L -> {
            engine.commands().reject(string(L, 1));
            return 0;
        });
    }

    /** "a\nb" -> [a, b]; "" -> []. */
    private static List<String> lines(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split("\n"));
    }
}
