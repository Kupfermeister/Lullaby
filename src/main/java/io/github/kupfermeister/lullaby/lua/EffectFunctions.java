package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.github.kupfermeister.lullaby.nms.Packets;
import io.github.kupfermeister.lullaby.nms.SoundSeeds;
import java.util.function.IntFunction;
import party.iroiro.luajava.Lua;

import static io.github.kupfermeister.lullaby.lua.JavaFunctions.add;
import static io.github.kupfermeister.lullaby.lua.JavaFunctions.slot;
import static io.github.kupfermeister.lullaby.lua.LuaText.string;

/** The J functions of effects.lua: chat messages and the packet actions (nms/Packets.java). */
final class EffectFunctions {

    private EffectFunctions() {
    }

    static void fill(Lua lua, Slots slots) {
        IntFunction<Object> slotLookup = slots::get;
        add(lua, "message", L -> {
            L.push(Nms.sendLiteral(slots.get(slot(L, 1)), string(L, 2)));
            return 1;
        });
        // Arguments: player slot, the component's wire bytes (a plain table in codec shape).
        add(lua, "message_component", L -> {
            L.push(Nms.sendMessage(slots.get(slot(L, 1)), Codecs.componentFromTable(Wire.read(L.toBuffer(2)), slotLookup)));
            return 1;
        });
        // Packet actions: each takes one wire table holding every field.
        add(lua, "particle", L -> {
            Packets.particle(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "sound", L -> {
            Packets.sound(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "precise_sound", L -> {
            Packets.preciseSound(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
        add(lua, "sound_seed", L -> {
            L.push((double) SoundSeeds.seed(string(L, 1), string(L, 2))); // below 2^48: exact as a Lua number
            return 1;
        });
        add(lua, "stop_sound", L -> {
            Packets.stopSound(Wire.read(L.toBuffer(1)), slotLookup);
            return 0;
        });
    }
}
