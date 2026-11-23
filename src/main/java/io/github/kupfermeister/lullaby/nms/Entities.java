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
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Entities for scripts: what an entity object's slot holds, the fields scripts read, the actions that change an entity
 * (modify_entity{ }, teleport{ }, velocity{ }, set_health{ }) and the entities of a chunk. A player is an entity too: a player object's slot holds its UUID (see objects.lua), every other entity's slot
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
    enum Field { UUID, TYPE, ALIVE, WORLD, POS, LOCATION, VELOCITY, ON_GROUND, HEALTH, MAX_HEALTH, ABSORPTION, CUSTOM_NAME,
        CUSTOM_NAME_VISIBLE, VEHICLE, GLOWING, SILENT, GRAVITY, INVULNERABLE, PERSISTENT, VISIBLE, COLLIDABLE, FIRE_TIME,
        FREEZE_TIME, FALL_DISTANCE, NO_DAMAGE_TIME, AIR, AI, AWARE, TARGET, CAN_PICKUP_ITEMS, ANGRY, LEFT_HANDED, FOOD,
        SATURATION, EXHAUSTION, XP, XP_LEVEL }

    private static final Field[] FIELDS = Field.values();

    private static final String MODIFY = "modify_entity{}";

    /**
     * What modify_entity{ } changes, owned here: the field name is the constant's name in lowercase, and each one is
     * also a {@link Field} of the same name, so a script reads back what it changed (e.fire_time). prepare checks the
     * value and the entity's kind and returns the change, which runs only after every field was checked.
     */
    private enum Change {
        CUSTOM_NAME { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            Object v = a.raw(f);
            Component name;
            if (v instanceof String s) name = Component.literal(s); // literal text, as /tellraw "Hi"
            else if (v instanceof Wire.Table) name = Codecs.componentFromTable(v, slots); // the library sent a component
            else if (Boolean.FALSE.equals(v)) name = null; // no name
            else throw a.wrong(f, "text, a component or false", v);
            return () -> e.setCustomName(name);
        } },
        CUSTOM_NAME_VISIBLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            return () -> e.setCustomNameVisible(on);
        } },
        GLOWING { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            return () -> e.setGlowingTag(on);
        } },
        SILENT { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            return () -> e.setSilent(on);
        } },
        GRAVITY { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            return () -> e.setNoGravity(!on);
        } },
        INVULNERABLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            return () -> e.setPermanentlyInvulnerable(on); // vanilla's Invulnerable tag
        } },
        PERSISTENT { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            Mob m = mob(e, f);
            return () -> m.persistenceRequired = on; // vanilla's PersistenceRequired tag
        } },
        VISIBLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            return () -> e.setInvisible(!on);
        } },
        COLLIDABLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            if (!(e instanceof LivingEntity living)) throw only(f, e, "living entities");
            return () -> living.collides = on; // CraftBukkit's flag, read by vanilla's push code on the server
        } },
        FIRE_TIME { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int t = a.whole(f, 0);
            return () -> e.setRemainingFireTicks(t);
        } },
        FREEZE_TIME { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int t = a.whole(f, 0);
            return () -> e.setTicksFrozen(t);
        } },
        FALL_DISTANCE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            double d = finite(a, f);
            return () -> e.fallDistance = d;
        } },
        NO_DAMAGE_TIME { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int t = a.whole(f, 0);
            return () -> e.setInvulnerableTime(t);
        } },
        AIR { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int t = a.whole(f, 0);
            return () -> e.setAirSupply(t);
        } },
        AI { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            Mob m = mob(e, f);
            return () -> m.setNoAi(!on);
        } },
        AWARE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            Mob m = mob(e, f);
            return () -> m.aware = on;
        } },
        TARGET { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            Object v = a.raw(f);
            Mob m = mob(e, f);
            LivingEntity target = null;
            // the library checked the value: an entity arrives as its slot number, no target as false
            if (v instanceof Number n) {
                Entity t = entity(slots.apply(n.intValue()));
                // FORMAT: modify_entity{} target that is gone
                if (t == null) throw new IllegalArgumentException(MODIFY + ": target is gone (dead, removed or unloaded)");
                if (!(t instanceof LivingEntity living))
                    throw Fields.wrong(MODIFY, f, "a living entity or false", "a " + EntityType.getKey(t.getType()));
                target = living;
            }
            LivingEntity to = target;
            return () -> m.setTarget(to);
        } },
        CAN_PICKUP_ITEMS { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            Mob m = mob(e, f);
            return () -> m.setCanPickUpLoot(on);
        } },
        ANGRY { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            if (!(e instanceof NeutralMob n)) throw only(f, e, "wolves, bees, endermen and other mobs that can turn angry");
            return on ? n::startPersistentAngerTimer : n::stopBeingAngry; // vanilla's own anger time
        } },
        LEFT_HANDED { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            Mob m = mob(e, f);
            return () -> m.setLeftHanded(on);
        } },
        FOOD { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int food = a.whole(f, 0);
            ServerPlayer p = player(e, f);
            return () -> p.getFoodData().setFoodLevel(food);
        } },
        SATURATION { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            float s = (float) finite(a, f);
            ServerPlayer p = player(e, f);
            return () -> p.getFoodData().setSaturation(s);
        } },
        EXHAUSTION { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            float x = (float) finite(a, f);
            ServerPlayer p = player(e, f);
            return () -> p.getFoodData().exhaustionLevel = x;
        } },
        XP { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int xp = a.whole(f, 0);
            ServerPlayer p = player(e, f);
            return () -> p.setExperiencePoints(xp); // as /xp set <n> points: kept inside the current level
        } },
        XP_LEVEL { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int level = a.whole(f, 0);
            ServerPlayer p = player(e, f);
            return () -> p.setExperienceLevels(level); // as /xp set <n> levels
        } };

        final String key = name().toLowerCase(Locale.ROOT);

        abstract Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots);
    }

    private static final Change[] CHANGES = Change.values();

    static {
        for (Change c : CHANGES) {
            try {
                Field.valueOf(c.name());
            } catch (IllegalArgumentException missing) {
                throw new IllegalStateException("Lullaby bug: modify_entity{ } changes " + c.key
                        + " but entities have no field of that name to read it back");
            }
        }
    }

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
            case ABSORPTION -> e instanceof LivingEntity living ? (Object) (double) living.getAbsorptionAmount() : null;
            case CUSTOM_NAME -> {
                Component name = e.getCustomName();
                yield name == null ? null : PaperAdventure.asAdventure(name);
            }
            case CUSTOM_NAME_VISIBLE -> e.isCustomNameVisible();
            case VEHICLE -> {
                Entity vehicle = e.getVehicle();
                yield vehicle == null ? null : vehicle.getBukkitEntity();
            }
            case GLOWING -> e.hasGlowingTag();
            case SILENT -> e.isSilent();
            case GRAVITY -> !e.isNoGravity();
            case INVULNERABLE -> e.isPermanentlyInvulnerable();
            case PERSISTENT -> e instanceof Mob m ? (Object) m.isPersistenceRequired() : null;
            case VISIBLE -> !e.isInvisible();
            case COLLIDABLE -> e instanceof LivingEntity living ? (Object) living.collides : null;
            case FIRE_TIME -> e.getRemainingFireTicks();
            case FREEZE_TIME -> e.getTicksFrozen();
            case FALL_DISTANCE -> e.fallDistance;
            case NO_DAMAGE_TIME -> e.getInvulnerableTime();
            case AIR -> e.getAirSupply();
            case AI -> e instanceof Mob m ? (Object) !m.isNoAi() : null;
            case AWARE -> e instanceof Mob m ? (Object) m.aware : null;
            case TARGET -> {
                LivingEntity target = e instanceof Mob m ? m.getTarget() : null;
                yield target == null ? null : target.getBukkitEntity();
            }
            case CAN_PICKUP_ITEMS -> e instanceof Mob m ? (Object) m.canPickUpLoot() : null;
            case ANGRY -> e instanceof NeutralMob n ? (Object) n.isAngry() : null;
            case LEFT_HANDED -> e instanceof Mob m ? (Object) m.isLeftHanded() : null;
            case FOOD -> e instanceof ServerPlayer p ? (Object) p.getFoodData().getFoodLevel() : null;
            case SATURATION -> e instanceof ServerPlayer p ? (Object) (double) p.getFoodData().getSaturationLevel() : null;
            case EXHAUSTION -> e instanceof ServerPlayer p ? (Object) (double) p.getFoodData().exhaustionLevel : null;
            case XP -> e instanceof ServerPlayer p ? (Object) Math.round(p.experienceProgress * p.getXpNeededForNextLevel()) : null;
            case XP_LEVEL -> e instanceof ServerPlayer p ? (Object) p.experienceLevel : null;
        };
    }

    /** The entity an action names: the library sends it as its slot number. null if it is gone. */
    private static Entity named(Fields a, IntFunction<Object> slots) {
        return entity(slots.apply(((Number) a.raw("entity")).intValue()));
    }

    private static double finite(Fields a, String f) {
        double d = a.number(f, 0);
        if (!Double.isFinite(d)) throw a.wrong(f, "a finite number", a.raw(f));
        return d;
    }

    private static Mob mob(Entity e, String f) {
        if (e instanceof Mob m) return m;
        throw only(f, e, "mobs");
    }

    private static ServerPlayer player(Entity e, String f) {
        if (e instanceof ServerPlayer p) return p;
        throw only(f, e, "players");
    }

    private static IllegalArgumentException only(String f, Entity e, String who) {
        // FORMAT: modify_entity{} field on an entity that doesn't have it
        return new IllegalArgumentException(MODIFY + ": " + f + ": a " + EntityType.getKey(e.getType())
                + " doesn't have it (only " + who + ")");
    }

    /**
     * modify_entity{ entity = e, fire_time = ..., ai = ..., food = ..., ... }: every value and the entity's kind are
     * checked before anything changes, so a wrong one never leaves half a change. Returns false if the entity is gone.
     */
    public static boolean modify(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields(MODIFY, wire);
        Entity e = named(a, slots);
        List<Change> given = new ArrayList<>();
        for (Change c : CHANGES) if (a.has(c.key)) given.add(c);
        a.done(); // refuses unknown fields (the error lists what modify_entity takes)
        if (e == null) return false;
        if (given.isEmpty()) {
            List<String> all = new ArrayList<>();
            for (Change c : CHANGES) all.add(c.key);
            // FORMAT: modify_entity{} with nothing to change
            throw new IllegalArgumentException(MODIFY + ": nothing to change (give " + Fields.names(all) + ")");
        }
        List<Runnable> changes = new ArrayList<>();
        for (Change c : given) changes.add(c.prepare(a, c.key, e, slots));
        for (Runnable change : changes) change.run();
        return true;
    }

    /**
     * teleport{ entity = e, location = l }: as vanilla's /tp. The library sends location as
     * { world slot, x, y, z, yaw, pitch } (Java can't see a world object's identity on the wire). Returns false if the
     * entity is gone or vanilla refused the teleport (another plugin can cancel it).
     */
    public static boolean teleport(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("teleport{}", wire);
        Entity e = named(a, slots);
        a.required("location", "a location");
        List<Object> l = a.list("location");
        a.done();
        @SuppressWarnings("unchecked")
        ResourceKey<Level> world = (ResourceKey<Level>) slots.apply(((Number) l.get(0)).intValue());
        ServerLevel level = server().getLevel(world);
        // FORMAT: teleport{} location in a world that isn't loaded
        if (level == null) throw new IllegalArgumentException("teleport{}: location's world isn't loaded");
        double[] at = {a.number(l.get(1), "location"), a.number(l.get(2), "location"),
                a.number(l.get(3), "location"), a.number(l.get(4), "location"), a.number(l.get(5), "location")};
        // FORMAT: teleport{} location outside vanilla's teleport limit (the same check as /tp)
        if (!Level.isInSpawnableBounds(BlockPos.containing(at[0], at[1], at[2])))
            throw new IllegalArgumentException("teleport{}: location is outside the world (/tp refuses it too)");
        return e != null && teleport(e, level, at);
    }

    /** velocity{ entity = e, velocity = v }: its new motion. Returns false if the entity is gone. */
    public static boolean velocity(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("velocity{}", wire);
        Entity e = named(a, slots);
        a.required("velocity", "a vec3");
        double[] v = a.vec3("velocity");
        a.done();
        if (e == null) return false;
        e.setDeltaMovement(v[0], v[1], v[2]);
        // vanilla's flag of markHurt (knockback): the tracker sends the new velocity to everyone who sees the entity
        // AND to the entity's own player, whose game moves them itself (needsSync would skip that player)
        e.syncVelocity = true;
        return true;
    }

    /**
     * set_health{ entity = e, health | add | full, max_health, absorption }: applied in that order of meaning: first
     * max_health, then the health itself, then absorption. Returns false if the entity is gone.
     */
    public static boolean setHealth(Object wire, IntFunction<Object> slots) {
        String what = "set_health{}";
        Fields a = new Fields(what, wire);
        Entity e = named(a, slots);
        Double max = a.has("max_health") ? finite(a, "max_health") : null;
        Double health = a.has("health") ? finite(a, "health") : null;
        Double add = a.has("add") ? finite(a, "add") : null;
        boolean full = a.bool("full", false);
        Double absorption = a.has("absorption") ? finite(a, "absorption") : null;
        a.done();
        if ((health != null ? 1 : 0) + (add != null ? 1 : 0) + (full ? 1 : 0) > 1) {
            // FORMAT: set_health{} with more than one of health, add and full
            throw new IllegalArgumentException(what + ": give only one of health, add or full");
        }
        if (max == null && health == null && add == null && !full && absorption == null) {
            // FORMAT: set_health{} with nothing to change
            throw new IllegalArgumentException(what + ": nothing to change (give health, add, full, max_health or absorption)");
        }
        // FORMAT: set_health{} absorption below 0
        if (absorption != null && absorption < 0) throw a.wrong("absorption", "0 or more (0: no yellow hearts)", a.raw("absorption"));
        if (e == null) return false;
        // FORMAT: set_health{} on an entity without health
        if (!(e instanceof LivingEntity living)) throw new IllegalArgumentException(what + ": a "
                + EntityType.getKey(e.getType()) + " has no health");

        if (max != null) {
            var attribute = living.getAttribute(Attributes.MAX_HEALTH);
            if (attribute != null) attribute.setBaseValue(max); // vanilla keeps it in the attribute's range, health under it
        }
        if (health != null) living.setHealth((float) (double) health); // vanilla keeps it in 0..max
        if (add != null) living.setHealth(living.getHealth() + (float) (double) add);
        if (full) living.setHealth(living.getMaxHealth());
        if (absorption != null) {
            // vanilla keeps absorption under the max_absorption attribute (0 unless an effect raises it): raise its base
            // just enough, so the hearts stay until they are hit away, as with a golden apple
            var cap = living.getAttribute(Attributes.MAX_ABSORPTION);
            if (cap != null && cap.getValue() < absorption) cap.setBaseValue(cap.getBaseValue() + absorption - cap.getValue());
            living.setAbsorptionAmount((float) (double) absorption);
        }
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
