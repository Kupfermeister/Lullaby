package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Input;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.addFree;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/** The J functions of events.lua: Paper events (EventBridge.java) and Lullaby's own input event (nms/Input.java). */
final class EventFunctions {

    private EventFunctions() {
    }

    static void fill(Lua lua, Engine engine, Slots slots) {
        EventBridge events = engine.events();
        events.slots(slots::add);
        // Names cross once (event_info at startup, event_fields and listen per event and handler kind); every event and
        // field read after that is whole numbers only (listener number, event slot, field number).
        addFree(lua, "event_info", L -> {
            push(L, EventBridge.info());
            return 1;
        });
        // The event's fields as "name=number ...", or nil and why it can't be used (unknown, off the main thread).
        add(lua, "event_fields", L -> {
            String name = string(L, 1), why = EventBridge.refusal(name);
            if (why != null) {
                L.pushNil();
                LuaText.push(L, why);
                return 2;
            }
            push(L, EventBridge.fields(name));
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
        add(lua, "event_set_item", L -> {
            events.setItem(slots.get(slot(L, 1)), (int) L.toInteger(2), slots.get(slot(L, 3)));
            return 0;
        });
        // event slot, field number, world slot, x, y, z, yaw, pitch
        add(lua, "event_set_location", L -> {
            events.setLocation(slots.get(slot(L, 1)), (int) L.toInteger(2), slots.get(slot(L, 3)), L.toNumber(4),
                    L.toNumber(5), L.toNumber(6), (float) L.toNumber(7), (float) L.toNumber(8));
            return 0;
        });
        // event slot, field number, x, y, z
        add(lua, "event_set_vec3", L -> {
            events.setVec3(slots.get(slot(L, 1)), (int) L.toInteger(2), L.toNumber(3), L.toNumber(4), L.toNumber(5));
            return 0;
        });
        // event slot, field number, the entity's (or player's) slot
        add(lua, "event_set_entity", L -> {
            events.setEntity(slots.get(slot(L, 1)), (int) L.toInteger(2), slots.get(slot(L, 3)));
            return 0;
        });
        // event slot, field number, vanilla's block state number
        add(lua, "event_set_block_state", L -> {
            events.setBlockState(slots.get(slot(L, 1)), (int) L.toInteger(2), (int) L.toInteger(3));
            return 0;
        });
        // Entry i (1-based) of the list field event_get just copied (events.lua reads them right away).
        add(lua, "event_list_item", L -> events.listItem(L, (int) L.toInteger(1)));
        // event slot, field number, the changed list's wire bytes (entries as EventBridge.setList describes)
        add(lua, "event_set_list", L -> {
            events.setList(slots.get(slot(L, 1)), (int) L.toInteger(2), Wire.read(L.toBuffer(3)), slots::get);
            return 0;
        });
        // event slot, field number, world slot, x, y, z, vanilla's block state number
        add(lua, "event_set_snapshot", L -> {
            events.setSnapshot(slots.get(slot(L, 1)), (int) L.toInteger(2), slots.get(slot(L, 3)), (int) L.toInteger(4),
                    (int) L.toInteger(5), (int) L.toInteger(6), (int) L.toInteger(7));
            return 0;
        });
        // Lullaby's own input event (nms/Input.java): the packets and fields once at startup, then numbers only.
        addFree(lua, "input_info", L -> {
            push(L, Input.info());
            return 1;
        });
        add(lua, "input_listen", L -> {
            engine.listenInput(L.toBoolean(1));
            return 0;
        });
        add(lua, "input_get", L -> ToLua.object(L, Input.read(slots.get(slot(L, 1)), (int) L.toInteger(2)), slots::add));
    }
}
