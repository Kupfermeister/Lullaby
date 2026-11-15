package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.function.ToIntFunction;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import party.iroiro.luajava.Lua;

/**
 * The one place that turns a Java object into a Lullaby object for Lua: event fields (EventBridge.java) and
 * java.wrap( ) (JavaDoor.java) both use it. Pushes a kind word and its values, and I.from_java (objects.lua) makes the
 * object from them. Main thread only.
 */
public final class ToLua {

    private ToLua() {
    }

    /**
     * Pushes player, world, block, location or item_stack with its values and returns how many it pushed; 0 (nothing
     * pushed) if Lullaby has no object for {@code o}. {@code addSlot} keeps an item stack copy (see Slots.java).
     */
    public static int object(Lua L, Object o, ToIntFunction<Object> addSlot) {
        switch (o) {
            case OfflinePlayer p -> {
                L.push("player");
                L.push(p.getUniqueId().toString());
                return 2;
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
            default -> {
                Object item = Nms.vanillaItem(o); // a copy: changing it in Lua doesn't change the Java stack
                if (item == null) return 0;
                L.push("item_stack");
                L.push(addSlot.applyAsInt(item));
                return 2;
            }
        }
    }
}
