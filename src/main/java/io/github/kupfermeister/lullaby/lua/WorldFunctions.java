package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.Worlds;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.pushOrNil;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The J functions of worlds.lua: world folders (nms/Worlds.java). Ids cross as text; a move_to location as world slot
 * (0: none), x, y, z, yaw, pitch. A copy runs on a worker thread: world_copy gives a job number, world_job its result.
 */
final class WorldFunctions {

    private WorldFunctions() {
    }

    static void fill(Lua lua, Engine engine, Slots slots) {
        Worlds worlds = engine.worlds();
        add(lua, "world_list", L -> {
            push(L, worlds.list());
            return 1;
        });
        add(lua, "world_create", L -> {
            push(L, worlds.create(Wire.read(L.toBuffer(1))));
            return 1;
        });
        add(lua, "world_load", L -> {
            push(L, worlds.load(string(L, 1)));
            return 1;
        });
        add(lua, "world_unload", L -> {
            L.push(worlds.unload(string(L, 1), L.toBoolean(2), location(L, 3, "unload_world{}", slots)));
            return 1;
        });
        add(lua, "world_delete", L -> {
            worlds.delete(string(L, 1), location(L, 2, "delete_world{}", slots));
            return 0;
        });
        add(lua, "world_copy", L -> {
            L.push(worlds.copy(string(L, 1), string(L, 2)));
            return 1;
        });
        add(lua, "world_job", L -> pushOrNil(L, worlds.job((int) L.toInteger(1))));
    }

    /** A move_to location from stack positions index (world slot, 0: none) to index + 5; null when none was given. */
    private static org.bukkit.Location location(Lua L, int index, String what, Slots slots) {
        int world = slot(L, index);
        if (world == 0) return null;
        org.bukkit.World bukkit = Nms.bukkitWorld(slots.get(world));
        // FORMAT: move_to in a world that isn't loaded
        if (bukkit == null) throw new IllegalArgumentException(what + ": move_to's world isn't loaded");
        return new org.bukkit.Location(bukkit, L.toNumber(index + 1), L.toNumber(index + 2), L.toNumber(index + 3),
                (float) L.toNumber(index + 4), (float) L.toNumber(index + 5));
    }
}
