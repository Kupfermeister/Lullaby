package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Codecs;
import io.github.kupfermeister.lullaby.nms.Entities;
import io.github.kupfermeister.lullaby.nms.Nms;
import io.papermc.paper.math.BlockPosition;
import java.nio.ByteBuffer;
import java.util.function.ToIntFunction;
import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import party.iroiro.luajava.Lua;

/**
 * The one place that turns a Java value into a Lullaby value for Lua: event fields (EventBridge.java), entity fields
 * (Entities.java) and java.wrap( ) (JavaDoor.java) all use it. Pushes a kind word and its values, and I.from_java
 * (objects.lua) makes the Lua value from them. Every conversion COPIES (Bukkit's Vector, Location and ItemStack can be
 * changed in Java; scripts never get them). Main thread only.
 */
public final class ToLua {

    private ToLua() {
    }

    /**
     * Pushes the kind word and values of {@code o} and returns how many it pushed; 0 (nothing: nil in Lua) for null
     * or a value Lullaby has nothing for. {@code addSlot} keeps an item stack copy or an entity (see Slots.java).
     */
    public static int object(Lua L, Object o, ToIntFunction<Object> addSlot) {
        switch (o) {
            case null -> {
                return 0;
            }
            case OfflinePlayer p -> { // also an online Player: one player object either way
                L.push("player");
                L.push(p.getUniqueId().toString());
                return 2;
            }
            case Entity e -> {
                L.push("entity");
                L.push(e.getUniqueId().toString());
                L.push(addSlot.applyAsInt(Entities.ref(e)));
                return 3;
            }
            case World w -> {
                L.push("world");
                L.push(Nms.worldIdOf(w));
                return 2;
            }
            case Block b -> {
                L.push("block");
                L.push(Nms.worldIdOf(b.getWorld()));
                L.push(b.getX());
                L.push(b.getY());
                L.push(b.getZ());
                return 5;
            }
            case Chunk c -> {
                L.push("chunk");
                L.push(Nms.worldIdOf(c.getWorld()));
                L.push(c.getX());
                L.push(c.getZ());
                return 4;
            }
            case Location l when l.getWorld() != null -> {
                L.push("location");
                L.push(Nms.worldIdOf(l.getWorld()));
                L.push((Number) l.getX());
                L.push((Number) l.getY());
                L.push((Number) l.getZ());
                L.push((Number) (double) l.getYaw());
                L.push((Number) (double) l.getPitch());
                return 7;
            }
            case Vector v -> {
                L.push("vec3");
                L.push((Number) v.getX());
                L.push((Number) v.getY());
                L.push((Number) v.getZ());
                return 4;
            }
            case BlockPosition p -> {
                L.push("block_pos");
                L.push(p.blockX());
                L.push(p.blockY());
                L.push(p.blockZ());
                return 4;
            }
            case BoundingBox b -> {
                L.push("aabb");
                L.push((Number) b.getMinX());
                L.push((Number) b.getMinY());
                L.push((Number) b.getMinZ());
                L.push((Number) b.getMaxX());
                L.push((Number) b.getMaxY());
                L.push((Number) b.getMaxZ());
                return 7;
            }
            case BlockData d -> {
                L.push("block_state");
                L.push(Nms.blockStateId(d));
                return 2;
            }
            case DamageSource d -> {
                // type, then direct and causing as uuid + slot each (slot 0: a player; both nil: none), then pos
                L.push("damage");
                L.push(d.getDamageType().getKey().toString());
                entityParts(L, d.getDirectEntity(), addSlot);
                entityParts(L, d.getCausingEntity(), addSlot);
                Location at = d.getDamageLocation();
                if (at == null) {
                    L.pushNil();
                    L.pushNil();
                    L.pushNil();
                } else {
                    L.push((Number) at.getX());
                    L.push((Number) at.getY());
                    L.push((Number) at.getZ());
                }
                return 9;
            }
            case org.bukkit.block.BlockState s -> { // Bukkit's snapshot of a block: where, and the state it holds
                L.push("block_snapshot");
                L.push(Nms.worldIdOf(s.getWorld()));
                L.push(s.getX());
                L.push(s.getY());
                L.push(s.getZ());
                L.push(Nms.blockStateId(s.getBlockData()));
                return 6;
            }
            case com.destroystokyo.paper.profile.PlayerProfile p -> {
                // name, uuid, then the skin ("textures" property: value and signature), each nil if missing
                L.push("profile");
                pushOrNil(L, p.getName());
                pushOrNil(L, p.getId() == null ? null : p.getId().toString());
                com.destroystokyo.paper.profile.ProfileProperty textures = null;
                for (var property : p.getProperties()) if (property.getName().equals("textures")) textures = property;
                pushOrNil(L, textures == null ? null : textures.getValue());
                pushOrNil(L, textures == null ? null : textures.getSignature());
                return 5;
            }
            case Component c -> {
                // a component table: wire bytes plus the count of item slots in show_item hovers
                Object[] out = Codecs.componentOut(Codecs.vanilla(c), addSlot);
                L.push("c");
                L.push((ByteBuffer) out[0]);
                L.push((int) out[1]);
                return 3;
            }
            case String s -> {
                L.push("v");
                LuaText.push(L, s);
                return 2;
            }
            case Boolean b -> {
                L.push("v");
                L.push(b);
                return 2;
            }
            case Number n -> {
                L.push("v");
                L.push(n);
                return 2;
            }
            default -> {
                Object item = Nms.vanillaItem(o); // a copy: changing it in Lua doesn't change the Java stack
                if (item == null) return 0;
                L.push("item_stack");
                L.push(addSlot.applyAsInt(item));
                return 2;
            }
        }
    }

    private static void pushOrNil(Lua L, String text) {
        if (text == null) L.pushNil();
        else LuaText.push(L, text);
    }

    /** An entity inside another value: its uuid and slot (slot 0: a player, found by its uuid), or nil, nil. */
    private static void entityParts(Lua L, Entity e, ToIntFunction<Object> addSlot) {
        if (e == null) {
            L.pushNil();
            L.pushNil();
            return;
        }
        L.push(e.getUniqueId().toString());
        L.push(e instanceof OfflinePlayer ? 0 : addSlot.applyAsInt(Entities.ref(e)));
    }

    /**
     * Pushes a list of entities as one text, "p UUID" (a player) or "e UUID SLOT" per line, so a chunk with thousands
     * of entities doesn't need thousands of Lua stack places. Returns 1.
     */
    public static int entities(Lua L, Iterable<Entity> list, ToIntFunction<Object> addSlot) {
        StringBuilder out = new StringBuilder();
        for (Entity e : list) {
            if (e instanceof OfflinePlayer) out.append("p ").append(e.getUniqueId()).append('\n');
            else out.append("e ").append(e.getUniqueId()).append(' ').append(addSlot.applyAsInt(Entities.ref(e))).append('\n');
        }
        L.push(out.toString());
        return 1;
    }
}
