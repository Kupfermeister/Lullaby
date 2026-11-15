package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.Packets;
import io.github.kupfermeister.lullaby.nms.ScriptCommands;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

    // The watchdog's flag is checked at the start of every function added with add() (see Watchdog.java). Set by
    // fill; main thread only. Functions the library needs while reporting or loading use addFree (no check).
    private static Watchdog watchdog;

    /** Fills the table on top of the stack with every function. */
    static void fill(Lua lua, Engine engine, Slots slots) {
        watchdog = engine.watchdog();
        EventBridge events = engine.events();
        events.slots(slots::add);

        addFree(lua, "set_time_limit", L -> {
            push(L, watchdog.setLimit(L.toNumber(1)));
            return 1;
        });
        addFree(lua, "log", L -> {
            engine.plugin().getComponentLogger().info(string(L, 1));
            return 0;
        });
        addFree(lua, "report", L -> {
            engine.errors().report(string(L, 1), string(L, 2), (int) L.toInteger(3), string(L, 4), string(L, 5));
            return 0;
        });
        addFree(lua, "set_error_window", L -> {
            engine.errors().setWindowSeconds(L.toNumber(1));
            return 0;
        });
        addFree(lua, "read_config", L -> {
            String config = engine.readConfig();
            if (config == null) return 0;
            push(L, config);
            return 1;
        });
        addFree(lua, "list_scripts", L -> {
            push(L, String.join("\n", engine.listScripts()));
            return 1;
        });
        addFree(lua, "read_script", L -> {
            watchdog.loading(string(L, 1)); // each script file gets its own time while loading
            push(L, engine.readScript(string(L, 1)));
            return 1;
        });

        addFree(lua, "wire_kinds", L -> {
            push(L, Codecs.kindList());
            return 1;
        });
        // Events: names cross once (event_info at startup, listen per handler kind); every event and field read
        // after that is whole numbers only (listener number, event slot, field number). See EventBridge.java.
        addFree(lua, "event_info", L -> {
            push(L, EventBridge.info());
            return 1;
        });
        add(lua, "listen", L -> {
            L.push(events.listen(string(L, 1), string(L, 2), string(L, 3)));
            return 1;
        });
        add(lua, "event_get", L -> events.get(L, slots.get(slot(L, 1)), (int) L.toInteger(2)));
        add(lua, "event_set", L -> {
            events.set(L, slots.get(slot(L, 1)), (int) L.toInteger(2), 3);
            return 0;
        });
        add(lua, "event_set_component", L -> {
            events.setComponent(slots.get(slot(L, 1)), (int) L.toInteger(2),
                    Codecs.adventure(Codecs.componentFromTable(Wire.read(L.toBuffer(3)), slots::get)));
            return 0;
        });
        // Slots (see Slots.java): objects are created in Lua, Java keeps what each number stands for.
        addFree(lua, "free_slots", L -> {
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
        // A command's check rejected a value: the script's text (literal, as /tellraw "text") to whoever typed it, in red.
        add(lua, "cmd_reject", L -> {
            engine.commands().reject(string(L, 1));
            return 0;
        });

        add(lua, "message", L -> {
            L.push(Nms.sendLiteral(slots.get(slot(L, 1)), string(L, 2)));
            return 1;
        });
        // Arguments: player slot, the component's wire bytes (a plain table in codec shape).
        add(lua, "message_component", L -> {
            L.push(Nms.sendMessage(slots.get(slot(L, 1)), Codecs.componentFromTable(Wire.read(L.toBuffer(2)), slots::get)));
            return 1;
        });

        // Codec values (see lazy.lua and nms/Codecs.java). Tables cross as wire bytes (Wire.java);
        // each value lives in a slot as an unchangeable Java copy (ItemStack, vanilla Component).
        IntFunction<Object> slotLookup = slots::get;
        add(lua, "slot_copy", L -> {
            L.push(slots.add(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_parse", L -> {
            L.push(slots.add(Codecs.itemParse(string(L, 1))));
            return 1;
        });
        add(lua, "item_from_table", L -> {
            L.push(slots.add(Codecs.itemFromTable(Wire.read(L.toBuffer(1)), slotLookup)));
            return 1;
        });
        // Arguments: base slot, then wire bytes of { id = , count = , components = } holding only the changed parts.
        add(lua, "item_build", L -> {
            Wire.Table parts = (Wire.Table) Wire.read(L.toBuffer(2));
            L.push(slots.add(Codecs.itemBuild(slots.get(slot(L, 1)), parts.hash.get("id"), parts.hash.get("count"),
                    parts.hash.get("components"), slotLookup)));
            return 1;
        });
        add(lua, "item_id", L -> {
            push(L, Codecs.itemId(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_count", L -> {
            L.push(Codecs.itemCount(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_empty", L -> {
            L.push(Codecs.itemEmpty(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_components", L -> {
            L.push(Codecs.itemComponents(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_text", L -> {
            push(L, Codecs.itemText(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "item_matches", L -> {
            L.push(Codecs.itemMatches(slots.get(slot(L, 1)), slots.get(slot(L, 2))));
            return 1;
        });
        // Components are plain Lua tables: they cross as wire bytes both ways. Results: the bytes
        // and how many show_item hovers hold a new item stack slot (lazy.lua turns those into item_stacks).
        ToIntFunction<Object> addSlot = slots::add;
        add(lua, "component_mini", L -> pushComponent(L, Codecs.componentOut(Codecs.componentMini(string(L, 1)), addSlot)));
        add(lua, "component_from_table", L -> pushComponent(L, Codecs.componentOut(
                Codecs.componentFromTable(Wire.read(L.toBuffer(1)), slotLookup), addSlot)));
        add(lua, "component_text", L -> {
            push(L, Codecs.componentText(Codecs.componentFromTable(Wire.read(L.toBuffer(1)), slotLookup)));
            return 1;
        });
        add(lua, "block_state_from_table", L -> {
            L.push(Codecs.blockStateFromTable(Wire.read(L.toBuffer(1)), slotLookup));
            return 1;
        });

        // Packet actions (nms/Packets.java): each takes one wire table holding every field.
        add(lua, "particle", L -> {
            Packets.particle(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "sound", L -> {
            Packets.sound(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "stop_sound", L -> {
            Packets.stopSound(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "from_json", L -> {
            Object value = Codecs.fromJson(string(L, 1), string(L, 2));
            Object[] text = Codecs.componentOutIfText(value, addSlot);
            if (text != null) return pushComponent(L, text);
            L.push(slots.add(value));
            return 1;
        });
    }

    /** A library function; a script past the time limit is stopped here, at its next call into the game. */
    private static void add(Lua lua, String name, JFunction function) {
        lua.push((JFunction) L -> {
            if (watchdog.expired()) {
                L.push(watchdog.stop());
                return -1;
            }
            try {
                return function.__call(L);
            } catch (RuntimeException e) {
                L.push(e.getMessage() != null ? e.getMessage() : e.toString());
                return -1;
            }
        });
        lua.setField(-2, name);
    }

    /** A function the library itself needs while loading or reporting errors: never stopped by the watchdog. */
    private static void addFree(Lua lua, String name, JFunction function) {
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

    private static int pushComponent(Lua L, Object[] out) {
        L.push((ByteBuffer) out[0]);
        L.push((int) out[1]);
        return 2;
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
