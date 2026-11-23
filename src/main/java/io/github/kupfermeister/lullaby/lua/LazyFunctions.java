package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Codecs;
import java.nio.ByteBuffer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.addFree;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The J functions of lazy.lua: codec values (nms/Codecs.java). Tables cross as wire bytes (Wire.java); each value
 * lives in a slot as an unchangeable Java copy (ItemStack, vanilla Component).
 */
final class LazyFunctions {

    private LazyFunctions() {
    }

    static void fill(Lua lua, Slots slots) {
        IntFunction<Object> slotLookup = slots::get;
        ToIntFunction<Object> addSlot = slots::add;
        addFree(lua, "wire_kinds", L -> {
            push(L, Codecs.kindList());
            return 1;
        });
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
        add(lua, "component_from_table", L -> pushComponent(L, Codecs.componentOut(
                Codecs.componentFromTable(Wire.read(L.toBuffer(1)), slotLookup), addSlot)));
        add(lua, "component_text", L -> {
            push(L, Codecs.componentText(Codecs.componentFromTable(Wire.read(L.toBuffer(1)), slotLookup)));
            return 1;
        });
        add(lua, "from_json", L -> {
            Object value = Codecs.fromJson(string(L, 1), string(L, 2));
            Object[] text = Codecs.componentOutIfText(value, addSlot);
            if (text != null) return pushComponent(L, text);
            L.push(slots.add(value));
            return 1;
        });
    }

    private static int pushComponent(Lua L, Object[] out) {
        L.push((ByteBuffer) out[0]);
        L.push((int) out[1]);
        return 2;
    }
}
