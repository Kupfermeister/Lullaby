package io.github.kupfermeister.lullaby.nms;

import io.github.kupfermeister.lullaby.lua.Wire;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Entities for scripts: what an entity object's slot holds, the fields scripts read, set{ } and the entities of a
 * chunk. A player is an entity too: a player object's slot holds its UUID (see objects.lua), every other entity's slot
 * a {@link Ref}. Main thread only (vanilla's entities are not thread-safe).
 *
 * <p>Identity is the UUID, as in vanilla's /tp and saves: the same Lua object stays valid when its chunk unloads and
 * loads again or the entity changes dimension (both give vanilla a new Java object with the same UUID).
 */
public final class Entities {

    /** What an entity object's slot holds: its UUID and the vanilla entity last seen with it. */
    public static final class Ref {
        final UUID uuid;
        Entity seen;

        Ref(Entity entity) {
            uuid = entity.getUUID();
            seen = entity;
        }
    }

    /**
     * The fields scripts read (e.pos, ...), owned here: the library reads the names once at startup
     * ({@link #fieldList}) and asks for a field by its number, never by name. The entity's doc block in objects.lua
     * describes each one; the docs build checks that both name the same fields.
     */
    enum Field { UUID, TYPE, ALIVE, WORLD, POS, LOCATION, VELOCITY, ON_GROUND, HEALTH, MAX_HEALTH, CUSTOM_NAME, VEHICLE }

    private static final Field[] FIELDS = Field.values();

    /** The fields set{ } takes besides entity, in the order it applies them (teleport first: /tp zeroes y speed). */
    private static final List<String> SETTABLE = List.of("location", "velocity", "health");
    private static final List<String> TAKES = java.util.stream.Stream.concat(java.util.stream.Stream.of("entity"),
            SETTABLE.stream()).toList();

    private Entities() {
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    /** "uuid=0 type=1 ..." for the library (once at startup). */
    public static String fieldList() {
        StringJoiner out = new StringJoiner(" ");
        for (Field f : FIELDS) out.add(f.name().toLowerCase(Locale.ROOT) + "=" + f.ordinal());
        return out.toString();
    }

    /** A slot for a Bukkit entity that is no player (players use their UUID, see ToLua.java). */
    public static Ref ref(org.bukkit.entity.Entity entity) {
        return new Ref(((org.bukkit.craftbukkit.entity.CraftEntity) entity).getHandle());
    }

    /**
     * The vanilla entity behind a slot's value (a player's UUID or a {@link Ref}); null if it is gone: offline,
     * dead and removed, or in a chunk that isn't loaded. Never loads a chunk.
     */
    static Entity entity(Object slotValue) {
        if (slotValue instanceof UUID uuid) return server().getPlayerList().getPlayer(uuid);
        Ref ref = (Ref) slotValue;
        Entity seen = ref.seen;
        if (seen != null && !seen.isRemoved()) return seen; // also true while a spawn event runs (not added yet)
        ref.seen = null;
        for (ServerLevel level : server().getAllLevels()) {
            Entity found = level.getEntities().get(ref.uuid);
            if (found != null && !found.isRemoved()) {
                ref.seen = found;
                return found;
            }
        }
        return null;
    }

    /** The Bukkit entity behind a slot's value (for event setters); null if it is gone. */
    public static org.bukkit.entity.Entity bukkitEntity(Object slotValue) {
        Entity entity = entity(slotValue);
        return entity == null ? null : entity.getBukkitEntity();
    }

    /** A loaded entity or online player by its UUID text, as a Bukkit entity (ToLua makes the object); null if none. */
    public static org.bukkit.entity.Entity find(String uuidText) {
        UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ServerPlayer player = server().getPlayerList().getPlayer(uuid);
        if (player != null) return player.getBukkitEntity();
        for (ServerLevel level : server().getAllLevels()) {
            Entity found = level.getEntities().get(uuid);
            if (found != null && !found.isRemoved()) return found.getBukkitEntity();
        }
        return null;
    }

    /**
     * A field's value for ToLua.java (Bukkit and plain Java values it knows); null reads as nil. A gone entity reads
     * nil everywhere except alive, which is false.
     */
    public static Object read(Object slotValue, int number) {
        Field field = FIELDS[number];
        Entity e = entity(slotValue);
        if (e == null) return field == Field.ALIVE ? Boolean.FALSE : null;
        return switch (field) {
            case UUID -> e.getUUID().toString();
            case TYPE -> EntityType.getKey(e.getType()).toString();
            case ALIVE -> e.isAlive();
            case WORLD -> e.level().getWorld();
            case POS -> new Vector(e.getX(), e.getY(), e.getZ());
            case LOCATION -> new Location(e.level().getWorld(), e.getX(), e.getY(), e.getZ(), e.getYRot(), e.getXRot());
            case VELOCITY -> {
                Vec3 v = e.getDeltaMovement();
                yield new Vector(v.x, v.y, v.z);
            }
            case ON_GROUND -> e.onGround();
            case HEALTH -> e instanceof LivingEntity living ? (Object) (double) living.getHealth() : null;
            case MAX_HEALTH -> e instanceof LivingEntity living ? (Object) (double) living.getMaxHealth() : null;
            case CUSTOM_NAME -> {
                Component name = e.getCustomName();
                yield name == null ? null : PaperAdventure.asAdventure(name);
            }
            case VEHICLE -> {
                Entity vehicle = e.getVehicle();
                yield vehicle == null ? null : vehicle.getBukkitEntity();
            }
        };
    }

    /**
     * set{ entity = e, location = ..., velocity = ..., health = ... }: every value is checked before anything changes,
     * so a wrong one never leaves half a change. The library sends entity as its slot number and location as
     * { world slot, x, y, z, yaw, pitch } (Java can't see a world object's identity on the wire). Returns false if
     * the entity is gone or vanilla refused the teleport (another plugin can cancel it).
     */
    public static boolean set(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("set{}", wire);
        a.only(TAKES);
        Object slotValue = slots.apply(((Number) a.raw("entity")).intValue());
        Entity e = entity(slotValue);
        if (e == null) return false;
        if (SETTABLE.stream().noneMatch(a::has)) {
            // FORMAT: set{} with nothing to change
            throw new IllegalArgumentException("set{}: nothing to change (give " + Fields.names(SETTABLE) + ")");
        }

        ServerLevel level = null;
        double[] at = null;
        if (a.has("location")) {
            List<Object> l = a.list("location");
            @SuppressWarnings("unchecked")
            ResourceKey<Level> world = (ResourceKey<Level>) slots.apply(((Number) l.get(0)).intValue());
            level = server().getLevel(world);
            // FORMAT: set{} location in a world that isn't loaded
            if (level == null) throw new IllegalArgumentException("set{}: location's world isn't loaded");
            at = new double[]{a.number(l.get(1), "location"), a.number(l.get(2), "location"),
                    a.number(l.get(3), "location"), a.number(l.get(4), "location"), a.number(l.get(5), "location")};
            // FORMAT: set{} location outside vanilla's teleport limit (the same check as /tp)
            if (!Level.isInSpawnableBounds(BlockPos.containing(at[0], at[1], at[2])))
                throw new IllegalArgumentException("set{}: location is outside the world (/tp refuses it too)");
        }
        double[] velocity = a.has("velocity") ? a.vec3("velocity") : null;
        Double health = null;
        if (a.has("health")) {
            double h = a.number("health", 0);
            // FORMAT: set{} health on an entity without health
            if (!(e instanceof LivingEntity)) throw new IllegalArgumentException("set{}: health: a "
                    + EntityType.getKey(e.getType()) + " has no health");
            if (!Double.isFinite(h)) throw a.wrong("health", "a finite number", a.raw("health"));
            health = h;
        }

        if (at != null) {
            if (!teleport(e, level, at)) return false;
            e = entity(slotValue); // a dimension change gives vanilla a new entity object
            if (e == null) return false;
        }
        if (velocity != null) {
            e.setDeltaMovement(velocity[0], velocity[1], velocity[2]);
            // vanilla's flag of markHurt (knockback): the tracker sends the new velocity to everyone who sees the entity
            // AND to the entity's own player, whose game moves them itself (needsSync would skip that player)
            e.syncVelocity = true;
        }
        if (health != null) ((LivingEntity) e).setHealth((float) (double) health); // vanilla keeps it in 0..max
        return true;
    }

    /** As vanilla's /tp does it (TeleportCommand.performTeleport), without its look-at part. */
    private static boolean teleport(Entity e, ServerLevel level, double[] at) {
        float yaw = net.minecraft.util.Mth.wrapDegrees((float) at[3]), pitch = net.minecraft.util.Mth.wrapDegrees((float) at[4]);
        if (!e.teleportTo(level, at[0], at[1], at[2], Set.of(), yaw, pitch, true)) return false;
        if (!(e instanceof LivingEntity living) || !living.isFallFlying()) {
            e.setDeltaMovement(e.getDeltaMovement().multiply(1, 0, 1));
            e.setOnGround(true);
        }
        if (e instanceof net.minecraft.world.entity.PathfinderMob mob) mob.getNavigation().stop();
        return true;
    }

    // ---- chunks (a chunk object is a world plus a chunk position, see objects.lua) ----

    /** Whether a chunk is loaded right now (never loads it). */
    public static boolean chunkLoaded(ResourceKey<Level> world, int x, int z) {
        ServerLevel level = server().getLevel(world);
        return level != null && level.getChunkSource().getChunkNow(x, z) != null;
    }

    /**
     * The entities standing in a loaded chunk (their position, not their hitbox, decides), as Bukkit entities for
     * ToLua; null if the chunk isn't loaded. Never loads a chunk.
     */
    public static List<org.bukkit.entity.Entity> chunkEntities(ResourceKey<Level> world, int x, int z) {
        ServerLevel level = server().getLevel(world);
        if (level == null || level.getChunkSource().getChunkNow(x, z) == null) return null;
        List<org.bukkit.entity.Entity> out = new ArrayList<>();
        AABB box = new AABB(x * 16.0, level.getMinY(), z * 16.0, x * 16.0 + 16, level.getMaxY() + 1, z * 16.0 + 16);
        level.getEntities().get(box, e -> {
            if (!e.isRemoved() && e.chunkPosition().x() == x && e.chunkPosition().z() == z) out.add(e.getBukkitEntity());
        });
        return out;
    }
}
