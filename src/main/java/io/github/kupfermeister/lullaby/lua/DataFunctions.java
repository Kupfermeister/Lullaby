package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.nms.EntityData;
import io.github.kupfermeister.lullaby.nms.SavedData;
import java.nio.ByteBuffer;
import java.util.UUID;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.addFree;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The J functions of data.lua: w.data, c.data and entity.data (nms/SavedData.java, nms/ChunkData.java,
 * nms/EntityData.java). A world slot, and a
 * chunk's x, z. A read gives true and the wire bytes (nil: nothing saved), or false when the world or chunk isn't
 * loaded.
 */
final class DataFunctions {

    private DataFunctions() {
    }

    static void fill(Lua lua, Engine engine, Slots slots) {
        // config data_max_size in megabytes (0: no limit), once per load
        addFree(lua, "data_limit", L -> {
            SavedData.setLimit(L.toNumber(1));
            return 0;
        });
        add(lua, "world_data_read", L -> pushRead(L, engine.readWorldData(slots.get(slot(L, 1)), slots::add)));
        add(lua, "world_data_loaded", L -> {
            engine.worldDataLoaded(slots.get(slot(L, 1)), string(L, 2));
            return 0;
        });
        add(lua, "chunk_data_read", L -> pushRead(L, engine.chunks().read(slots.get(slot(L, 1)), (int) L.toInteger(2),
                (int) L.toInteger(3), slots::add)));
        add(lua, "chunk_data_loaded", L -> {
            engine.chunks().loaded(slots.get(slot(L, 1)), (int) L.toInteger(2), (int) L.toInteger(3), string(L, 4));
            return 0;
        });
        add(lua, "chunk_data_reattach", L -> {
            L.push(engine.chunks().reattach(slots.get(slot(L, 1)), (int) L.toInteger(2), (int) L.toInteger(3)));
            return 1;
        });
        // entity.data / player.data: uuid text, is it a player, the data's name in errors -> state (EntityData's
        // NOT_LOADED, READ, NO_FILE) and, when read, the wire bytes (nil: nothing saved)
        add(lua, "entity_data_read", L -> {
            Object[] read = engine.entityData().read(UUID.fromString(string(L, 1)), L.toBoolean(2), string(L, 3),
                    slots::add);
            L.push((Integer) read[0]);
            if ((Integer) read[0] != EntityData.READ) return 1;
            if (read[1] == null) L.pushNil();
            else L.push((ByteBuffer) read[1]);
            return 2;
        });
        add(lua, "entity_data_loaded", L -> {
            engine.entityData().loaded(UUID.fromString(string(L, 1)), string(L, 2));
            return 0;
        });
    }

    /** A saved data read ({loaded, bytes or null}): false, or true and the bytes (nil: nothing saved). */
    private static int pushRead(Lua L, Object[] read) {
        if (!(Boolean) read[0]) {
            L.push(false);
            return 1;
        }
        L.push(true);
        if (read[1] == null) L.pushNil();
        else L.push((ByteBuffer) read[1]);
        return 2;
    }
}
