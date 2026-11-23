package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Entities;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.List;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.pushOrNil;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/** The J functions of blocks.lua: world objects, block states, blocks and chunks. */
final class BlockFunctions {

    private BlockFunctions() {
    }

    static void fill(Lua lua, Slots slots) {
        add(lua, "world_id", L -> pushOrNil(L, Nms.worldId(string(L, 1))));
        add(lua, "world_slot", L -> {
            L.push(slots.add(Nms.worldKey(string(L, 1))));
            return 1;
        });
        // Block states travel as vanilla's state numbers (Block.getId); blocks.lua keeps one table per number.
        add(lua, "block_state_parse", L -> {
            L.push(Nms.blockStateParse(string(L, 1)));
            return 1;
        });
        add(lua, "block_state_from_table", L -> {
            L.push(Codecs.blockStateFromTable(Wire.read(L.toBuffer(1)), slots::get));
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
        // Chunks: a world slot and the chunk's x, z (a chunk object has no slot of its own, like a block).
        add(lua, "chunk_loaded", L -> {
            L.push(Entities.chunkLoaded(slots.get(slot(L, 1)), (int) L.toInteger(2), (int) L.toInteger(3)));
            return 1;
        });
        add(lua, "chunk_entities", L -> {
            List<org.bukkit.entity.Entity> list = Entities.chunkEntities(slots.get(slot(L, 1)), (int) L.toInteger(2),
                    (int) L.toInteger(3));
            return list == null ? 0 : ToLua.entities(L, list, slots::add);
        });
    }
}
