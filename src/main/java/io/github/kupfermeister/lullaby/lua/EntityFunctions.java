package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.ClientEntities;
import io.github.kupfermeister.lullaby.nms.Entities;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.UUID;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.addFree;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.pushOrNil;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.push;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/**
 * The J functions of entities.lua: players and entities (nms/Entities.java). An entity's slot holds an Entities.Ref,
 * a player's its UUID; fields go by number.
 */
final class EntityFunctions {

    private EntityFunctions() {
    }

    static void fill(Lua lua, Slots slots) {
        addFree(lua, "entity_fields", L -> {
            push(L, Entities.fieldList());
            return 1;
        });
        add(lua, "entity_get", L -> ToLua.object(L, Entities.read(slots.get(slot(L, 1)), (int) L.toInteger(2)), slots::add));
        add(lua, "entity_find", L -> ToLua.object(L, Entities.find(string(L, 1)), slots::add));
        add(lua, "entity_modify", L -> {
            L.push(Entities.modify(Wire.read(L.toBuffer(1)), slots::get));
            return 1;
        });
        add(lua, "entity_teleport", L -> {
            L.push(Entities.teleport(Wire.read(L.toBuffer(1)), slots::get));
            return 1;
        });
        addFree(lua, "blueprint_fields", L -> {
            push(L, Entities.blueprintFields());
            return 1;
        });
        add(lua, "blueprint_check", L -> {
            push(L, Entities.blueprintCheck(Wire.read(L.toBuffer(1)), slots::get));
            return 1;
        });
        add(lua, "entity_summon", L -> ToLua.object(L, Entities.summon(Wire.read(L.toBuffer(1)), slots::get), slots::add));
        add(lua, "entity_remove", L -> {
            L.push(Entities.remove(Wire.read(L.toBuffer(1)), slots::get));
            return 1;
        });
        // Arguments: wire bytes, the config's clientside_entity_limit (-1: none). Results: uuid text, new slot.
        add(lua, "client_summon", L -> {
            ClientEntities.Fake f = ClientEntities.summon(Wire.read(L.toBuffer(1)), slots::get, (int) L.toInteger(2));
            push(L, Entities.read(f, 0).toString()); // field 0 is uuid
            L.push(slots.add(f));
            return 2;
        });
        add(lua, "client_viewers", L -> {
            push(L, ClientEntities.viewerList(slots.get(slot(L, 1))));
            return 1;
        });
        add(lua, "player_slot", L -> {
            L.push(slots.add(UUID.fromString(string(L, 1))));
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
    }
}
