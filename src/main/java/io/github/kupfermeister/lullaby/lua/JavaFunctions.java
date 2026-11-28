package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.nms.Fields;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.List;
import party.iroiro.luajava.JFunction;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The raw Java functions the Lua library calls (the table J in the library files). Scripts never see them: the
 * library checks arguments in Lua and calls these with plain values (one Lua-Java trip per library call).
 *
 * <p>Each library file's J functions live in the Java file of the same topic: events.lua → {@link EventFunctions},
 * entities.lua → {@link EntityFunctions}, blocks.lua → {@link BlockFunctions}, effects.lua → {@link EffectFunctions},
 * lazy.lua → {@link LazyFunctions}, commands.lua → {@link CommandFunctions}, data.lua → {@link DataFunctions},
 * worlds.lua → {@link WorldFunctions}. This file holds the ones of core.lua, values.lua, loader.lua and java.lua, and
 * {@link #add}, which every one of them uses.
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
        // config block_write_ms, block_show_sections, lullablend_port (0: off), lullablend_bind; once per load.
        // Gives back nil, or a warning text when LullaBlend's port can't be opened.
        addFree(lua, "blocks_config", L -> {
            io.github.kupfermeister.lullaby.nms.BlockEngine.configure((int) L.toInteger(1), (int) L.toInteger(2));
            String problem = io.github.kupfermeister.lullaby.lullablend.LullaBlend.configure((int) L.toInteger(3), string(L, 4));
            if (problem == null) return 0;
            push(L, problem);
            return 1;
        });
        // config custom_hardness (true/false); once per load. TEST: holds every game's break speed at 0 until the
        // player starts hitting a block (nms/BreakSpeed.java).
        addFree(lua, "break_speed_config", L -> {
            io.github.kupfermeister.lullaby.nms.BreakSpeed.configure(engine.plugin(), L.toBoolean(1));
            return 0;
        });
        // config sound_mosaicing (the whole section as one wire table); once per load. nil, or a warning text.
        // Sound mosaicing: remove these lines to remove it (see mosaic/package-info.java).
        addFree(lua, "mosaic_config", L -> {
            String problem = io.github.kupfermeister.lullaby.mosaic.Mosaic.configure(Wire.read(L.toBuffer(1)));
            if (problem == null) return 0;
            push(L, problem);
            return 1;
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
        // Slots (see Slots.java): objects are created in Lua, Java keeps what each number stands for.
        addFree(lua, "free_slots", L -> {
            slots.free(string(L, 1));
            return 0;
        });
        // Arguments: what ("message{}"), the unknown key as text, the names it takes (one per line). Gives the error
        // text, so actions checked in Lua word it exactly as Fields does for the others.
        add(lua, "unknown_field", L -> {
            LuaText.push(L, Fields.unknownField(string(L, 1), string(L, 2), List.of(string(L, 3).split("\n"))));
            return 1;
        });
        add(lua, "dimension_id", L -> pushOrNil(L, Nms.dimensionId(string(L, 1))));
        // The raw Java door (config unlock.java): only called when unlocked, so when locked none of it exists.
        addFree(lua, "java_door", L -> {
            JavaDoor.fill(L, slots);
            return 1;
        });

        EventFunctions.fill(lua, engine, slots);
        EntityFunctions.fill(lua, slots);
        BlockFunctions.fill(lua, slots);
        EffectFunctions.fill(lua, slots);
        LazyFunctions.fill(lua, slots);
        CommandFunctions.fill(lua, engine, slots);
        DataFunctions.fill(lua, engine, slots);
        WorldFunctions.fill(lua, engine, slots);
    }

    /**
     * A library function (also each raw Java door function, see JavaDoor.java); a script past the time limit is
     * stopped here, at its next call into the game. LinkageError: a class missing at runtime (raw door calls).
     */
    static void add(Lua lua, String name, JFunction function) {
        lua.push((JFunction) L -> {
            if (watchdog.expired()) {
                L.push(watchdog.stop());
                return -1;
            }
            try {
                return function.__call(L);
            } catch (RuntimeException | LinkageError e) {
                L.push(e.getMessage() != null ? e.getMessage() : e.toString());
                return -1;
            }
        });
        lua.setField(-2, name);
    }

    /** A function the library itself needs while loading or reporting errors: never stopped by the watchdog. */
    static void addFree(Lua lua, String name, JFunction function) {
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

    static int slot(Lua L, int index) {
        return (int) L.toInteger(index);
    }

    static int pushOrNil(Lua L, String text) {
        if (text == null) return 0;
        push(L, text);
        return 1;
    }
}
