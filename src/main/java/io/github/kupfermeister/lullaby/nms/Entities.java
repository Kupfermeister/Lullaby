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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
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
 * (modify_entity{ }, teleport{ }, remove_entity{ }), making one (summon{ }, and the check of blueprint{ }) and the
 * entities of a chunk. A player is an entity too: a player object's slot holds its UUID (see entities.lua), every other
 * entity's slot a {@link Ref}, a clientside entity's a {@link ClientEntities.Fake} (its vanilla entity is never in a
 * world, see ClientEntities.java). Main thread only (vanilla's entities are not thread-safe).
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
     * ({@link #fieldList}) and asks for a field by its number, never by name. The entity's doc block in entities.lua
     * describes each one; the docs build checks that both name the same fields. DATA (saved data) and BLUEPRINT (a
     * copy of the changeable fields, made from the other fields) are answered by the library and never read here;
     * they are listed so the field list stays in one place.
     */
    enum Field { UUID, DATA, BLUEPRINT, TYPE, ALIVE, WORLD, POS, LOCATION, VELOCITY, ON_GROUND, HEALTH, MAX_HEALTH, ABSORPTION,
        CUSTOM_NAME, CUSTOM_NAME_VISIBLE, VEHICLE, GLOWING, SILENT, GRAVITY, INVULNERABLE, PERSISTENT, VISIBLE, COLLIDABLE,
        FIRE_TIME, FREEZE_TIME, FALL_DISTANCE, NO_DAMAGE_TIME, AIR, AI, AWARE, TARGET, CAN_PICKUP_ITEMS, ANGRY, LEFT_HANDED,
        FOOD, SATURATION, EXHAUSTION, XP, XP_LEVEL, DISPLAY_DATA }

    private static final Field[] FIELDS = Field.values();

    private static final String MODIFY = "modify_entity{}";

    /**
     * Thrown by prepare when the entity doesn't have the field (food on a zombie, ai on an item): the field is then
     * ignored, after its value was checked (jft 2026-11-23: unsupported fields are ignored, never an error; a name
     * no action takes at all is still an error). No stack trace: it is not an error.
     */
    private static final RuntimeException SKIP = new RuntimeException("skip", null, false, false) {
    };

    /**
     * What modify_entity{ } changes, owned here: the field name is the constant's name in lowercase, and each one is
     * also a {@link Field} of the same name, so a script reads back what it changed (e.fire_time). The same names are
     * what blueprint{ }, summon{ } and clientside_summon{ } take (all but those with {@code blueprint} false). prepare
     * checks the value and returns the change, which runs only after every field was checked; a field the entity
     * doesn't have throws {@link #SKIP}. The changes run in this order: max_health before health, so health = 40 fits
     * a max_health = 40 given in the same call.
     */
    private enum Change {
        MAX_HEALTH { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            double max = finite(a, f);
            LivingEntity living = living(e);
            var attribute = living.getAttribute(Attributes.MAX_HEALTH);
            // vanilla keeps the base in the attribute's range (1 to 1024) and health under the new max
            return () -> { if (attribute != null) attribute.setBaseValue(max); };
        } },
        HEALTH { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            float health = (float) finite(a, f);
            LivingEntity living = living(e);
            return () -> living.setHealth(health); // vanilla keeps it in 0..max_health; 0 kills
        } },
        ABSORPTION { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            double absorption = finite(a, f);
            // FORMAT: absorption below 0
            if (absorption < 0) throw a.wrong(f, "0 or more (0: no yellow hearts)", a.raw(f));
            LivingEntity living = living(e);
            return () -> {
                // vanilla keeps absorption under the max_absorption attribute (0 unless an effect raises it): raise its
                // base just enough, so the hearts stay until they are hit away, as with a golden apple
                var cap = living.getAttribute(Attributes.MAX_ABSORPTION);
                if (cap != null && cap.getValue() < absorption) cap.setBaseValue(cap.getBaseValue() + absorption - cap.getValue());
                living.setAbsorptionAmount((float) absorption);
            };
        } },
        VELOCITY { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            double[] v = a.vec3(f);
            return () -> {
                e.setDeltaMovement(v[0], v[1], v[2]);
                // vanilla's flag of markHurt (knockback): the tracker sends the new velocity to everyone who sees the
                // entity AND to the entity's own player, whose game moves them itself (needsSync would skip that player)
                e.syncVelocity = true;
            };
        } },
        CUSTOM_NAME { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            Component name = text(a, f, slots);
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
            Mob m = mob(e);
            return () -> m.persistenceRequired = on; // vanilla's PersistenceRequired tag
        } },
        VISIBLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            return () -> e.setInvisible(!on);
        } },
        COLLIDABLE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            LivingEntity living = living(e);
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
            Mob m = mob(e);
            return () -> m.setNoAi(!on);
        } },
        AWARE { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, true);
            Mob m = mob(e);
            return () -> m.aware = on;
        } },
        TARGET(false) { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            Object v = a.raw(f);
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
            Mob m = mob(e);
            LivingEntity to = target;
            return () -> m.setTarget(to);
        } },
        CAN_PICKUP_ITEMS { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            Mob m = mob(e);
            return () -> m.setCanPickUpLoot(on);
        } },
        ANGRY { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            if (!(e instanceof NeutralMob n)) throw SKIP;
            return on ? n::startPersistentAngerTimer : n::stopBeingAngry; // vanilla's own anger time
        } },
        LEFT_HANDED { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            boolean on = a.bool(f, false);
            Mob m = mob(e);
            return () -> m.setLeftHanded(on);
        } },
        FOOD { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int food = a.whole(f, 0);
            ServerPlayer p = player(e);
            return () -> p.getFoodData().setFoodLevel(food);
        } },
        SATURATION { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            float s = (float) finite(a, f);
            ServerPlayer p = player(e);
            return () -> p.getFoodData().setSaturation(s);
        } },
        EXHAUSTION { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            float x = (float) finite(a, f);
            ServerPlayer p = player(e);
            return () -> p.getFoodData().exhaustionLevel = x;
        } },
        XP { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int xp = a.whole(f, 0);
            ServerPlayer p = player(e);
            return () -> p.setExperiencePoints(xp); // as /xp set <n> points: kept inside the current level
        } },
        XP_LEVEL { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            int level = a.whole(f, 0);
            ServerPlayer p = player(e);
            return () -> p.setExperienceLevels(level); // as /xp set <n> levels
        } },
        DISPLAY_DATA { Runnable prepare(Fields a, String f, Entity e, IntFunction<Object> slots) {
            Runnable change = DisplayData.prepare(a, f, e, slots);
            if (change == null) throw SKIP;
            return change;
        } };

        final String key = name().toLowerCase(Locale.ROOT);
        /** Whether blueprints hold it (not target: it names another entity, and a blueprint holds only values). */
        final boolean blueprint;

        Change() {
            this(true);
        }

        Change(boolean blueprint) {
            this.blueprint = blueprint;
        }

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

    /** The fields a blueprint holds, besides type ("max_health health ..."), for the library (once at startup). */
    public static String blueprintFields() {
        StringJoiner out = new StringJoiner(" ");
        for (Change c : CHANGES) if (c.blueprint) out.add(c.key);
        return out.toString();
    }

    /** A slot for a Bukkit entity that is no player (players use their UUID, see ToLua.java). */
    public static Ref ref(org.bukkit.entity.Entity entity) {
        return new Ref(((org.bukkit.craftbukkit.entity.CraftEntity) entity).getHandle());
    }

    /**
     * The vanilla entity behind a slot's value (a player's UUID, a {@link Ref} or a clientside entity); null if it is
     * gone: offline, dead and removed, in a chunk that isn't loaded, or a removed clientside entity. Never loads a
     * chunk. A clientside entity gives its vanilla entity, which is in no world (sound{ } plays at it by its number).
     */
    static Entity entity(Object slotValue) {
        if (slotValue instanceof UUID uuid) return server().getPlayerList().getPlayer(uuid);
        if (slotValue instanceof ClientEntities.Fake fake) return fake.removed ? null : fake.body;
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
     * nil everywhere except alive, which is false. A clientside entity reads what the server last sent.
     */
    public static Object read(Object slotValue, int number) {
        Field field = FIELDS[number];
        Entity e = entity(slotValue);
        if (e == null) return field == Field.ALIVE ? Boolean.FALSE : null;
        return switch (field) {
            case UUID -> e.getUUID().toString();
            case DATA, BLUEPRINT -> throw new IllegalStateException("Lullaby bug: entity." + field.name().toLowerCase(Locale.ROOT)
                    + " is answered by the library");
            case TYPE -> EntityType.getKey(e.getType()).toString();
            case ALIVE -> slotValue instanceof ClientEntities.Fake || e.isAlive();
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
            case DISPLAY_DATA -> {
                var data = DisplayData.read(e);
                yield data == null ? null : new Wire.Tree(data);
            }
        };
    }

    /** The slot value an action names: the library sends the entity as its slot number. */
    private static Object named(Fields a, IntFunction<Object> slots) {
        return slots.apply(((Number) a.raw("entity")).intValue());
    }

    private static double finite(Fields a, String f) {
        double d = a.number(f, 0);
        if (!Double.isFinite(d)) throw a.wrong(f, "a finite number", a.raw(f));
        return d;
    }

    private static Mob mob(Entity e) {
        if (e instanceof Mob m) return m;
        throw SKIP;
    }

    private static LivingEntity living(Entity e) {
        if (e instanceof LivingEntity l) return l;
        throw SKIP;
    }

    private static ServerPlayer player(Entity e) {
        if (e instanceof ServerPlayer p) return p;
        throw SKIP;
    }

    /** Text: a string (literal, as /tellraw "Hi"), a component table, or false (none: null). Also display_data's text. */
    static Component text(Fields a, String f, IntFunction<Object> slots) {
        Object v = a.raw(f);
        if (v instanceof String s) return Component.literal(s);
        if (v instanceof Wire.Table) return Codecs.componentFromTable(v, slots); // the library sent a component
        if (Boolean.FALSE.equals(v)) return null;
        throw a.wrong(f, "text, a component or false", v);
    }

    /**
     * Reads every change field of {@code a} (so done() knows them) and checks them against {@code e}; returns the
     * changes in their order, which the caller runs after done(). {@code fake}: e is a clientside entity's (target
     * is ignored then: a real mob can't go after something only players' games know). {@code blueprint}: only the
     * fields a blueprint holds are read (target is then an unknown field).
     */
    static List<Runnable> prepare(Fields a, Entity e, IntFunction<Object> slots, boolean fake, boolean blueprint) {
        List<Runnable> changes = new ArrayList<>();
        for (Change c : CHANGES) {
            if (blueprint && !c.blueprint) continue;
            if (!a.has(c.key)) continue;
            if (fake && c == Change.TARGET) continue;
            try {
                changes.add(c.prepare(a, c.key, e, slots));
            } catch (RuntimeException skip) {
                if (skip != SKIP) throw skip;
            }
        }
        return changes;
    }

    /** Whether any change field is in {@code a} (reads them all, so done() knows them). */
    private static boolean anyChange(Fields a) {
        boolean any = false;
        for (Change c : CHANGES) if (a.has(c.key)) any = true;
        return any;
    }

    /**
     * modify_entity{ entity = e, health = ..., velocity = ..., ai = ..., food = ..., ... }: every value is checked
     * before anything changes, so a wrong one never leaves half a change; a field this entity doesn't have is ignored.
     * Returns false if the entity is gone (a removed clientside entity too).
     */
    public static boolean modify(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields(MODIFY, wire);
        Object slotValue = named(a, slots);
        boolean any = anyChange(a);
        a.done(); // refuses unknown fields (the error lists what modify_entity takes)
        if (!any) {
            List<String> all = new ArrayList<>();
            for (Change c : CHANGES) all.add(c.key);
            // FORMAT: modify_entity{} with nothing to change
            throw new IllegalArgumentException(MODIFY + ": nothing to change (give " + Fields.names(all) + ")");
        }
        Entity e = entity(slotValue);
        if (e == null) return false;
        boolean fake = slotValue instanceof ClientEntities.Fake;
        List<Runnable> changes = prepare(a, e, slots, fake, false);
        for (Runnable change : changes) change.run();
        if (fake) ClientEntities.changed((ClientEntities.Fake) slotValue, a.has("velocity"));
        return true;
    }

    /** Where to: a world loaded now and a position inside the world (the same check as /tp), plus yaw and pitch. */
    record Place(ServerLevel level, double x, double y, double z, float yaw, float pitch) {
    }

    /**
     * The location field: the library sends it as { world slot, x, y, z, yaw, pitch } (Java can't see a world
     * object's identity on the wire). {@code what}: the action's name for errors.
     */
    @SuppressWarnings("unchecked")
    static Place place(Fields a, String what, IntFunction<Object> slots) {
        a.required("location", "a location");
        List<Object> l = a.list("location");
        ResourceKey<Level> world = (ResourceKey<Level>) slots.apply(((Number) l.get(0)).intValue());
        ServerLevel level = server().getLevel(world);
        // FORMAT: location in a world that isn't loaded
        if (level == null) throw new IllegalArgumentException(what + ": location's world isn't loaded");
        double x = a.number(l.get(1), "location"), y = a.number(l.get(2), "location"), z = a.number(l.get(3), "location");
        float yaw = net.minecraft.util.Mth.wrapDegrees((float) a.number(l.get(4), "location"));
        float pitch = net.minecraft.util.Mth.wrapDegrees((float) a.number(l.get(5), "location"));
        // FORMAT: location outside vanilla's teleport limit (the same check as /tp)
        if (!Level.isInSpawnableBounds(BlockPos.containing(x, y, z)))
            throw new IllegalArgumentException(what + ": location is outside the world (/tp refuses it too)");
        return new Place(level, x, y, z, yaw, pitch);
    }

    /**
     * teleport{ entity = e, location = l }: as vanilla's /tp. Returns false if the entity is gone or vanilla refused
     * the teleport (another plugin can cancel it). A clientside entity moves on its viewers' screens.
     */
    public static boolean teleport(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("teleport{}", wire);
        Object slotValue = named(a, slots);
        Place to = place(a, "teleport{}", slots);
        a.done();
        Entity e = entity(slotValue);
        if (e == null) return false;
        if (slotValue instanceof ClientEntities.Fake fake) return ClientEntities.teleport(fake, to);
        return teleport(e, to);
    }

    /** As vanilla's /tp does it (TeleportCommand.performTeleport), without its look-at part. */
    private static boolean teleport(Entity e, Place to) {
        if (!e.teleportTo(to.level(), to.x(), to.y(), to.z(), Set.of(), to.yaw(), to.pitch(), true)) return false;
        if (!(e instanceof LivingEntity living) || !living.isFallFlying()) {
            e.setDeltaMovement(e.getDeltaMovement().multiply(1, 0, 1));
            e.setOnGround(true);
        }
        if (e instanceof net.minecraft.world.entity.PathfinderMob mob) mob.getNavigation().stop();
        return true;
    }

    /**
     * The type field: an entity id ("zombie" or "minecraft:zombie") that /summon can make. Players can't be made
     * (and a few other kinds vanilla's /summon refuses, like fishing bobbers).
     */
    static EntityType<?> type(Fields a, String what) {
        Object v = a.required("type", "an entity id like \"zombie\"");
        Identifier id = v instanceof String s ? Identifier.tryParse(s) : null;
        if (id == null) throw a.wrong("type", "an entity id like \"zombie\"", v);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        // FORMAT: an entity id that doesn't exist
        if (type == null) throw new IllegalArgumentException(what + ": type: there is no entity \"" + v + "\"");
        // FORMAT: an entity kind that can't be made (player, fishing bobber, ...)
        if (!type.canSummon()) throw new IllegalArgumentException(what + ": type: a " + id + " can't be made (/summon can't either)");
        return type;
    }

    /** A new vanilla entity of the type at the place, in no world yet; the error if vanilla can't make it. */
    static Entity create(EntityType<?> type, Place at, String what) {
        Entity e = type.create(at.level(), EntitySpawnReason.COMMAND);
        // FORMAT: vanilla couldn't make the entity
        if (e == null) throw new IllegalArgumentException(what + ": type: a " + EntityType.getKey(type) + " can't be made");
        e.snapTo(at.x(), at.y(), at.z(), at.yaw(), at.pitch());
        e.setYHeadRot(at.yaw());
        e.setYBodyRot(at.yaw());
        return e;
    }

    /**
     * blueprint{ type = , ... }: checks the type and every field (on a throwaway entity, never added to a world) and
     * returns the type's full id ("minecraft:zombie"). The library keeps the blueprint; summon{ } and
     * clientside_summon{ } send its fields again.
     */
    public static String blueprintCheck(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("blueprint{}", wire);
        EntityType<?> type = type(a, "blueprint{}");
        ServerLevel level = server().overworld();
        Entity e = create(type, new Place(level, 0, 0, 0, 0, 0), "blueprint{}");
        prepare(a, e, slots, false, true);
        a.done();
        return EntityType.getKey(type).toString();
    }

    /**
     * summon{ type = , location = , ...fields } (the library puts a blueprint's fields in): a real entity, made as
     * vanilla's /summon makes it (a mob gets its random spawn setup), then changed by the fields, then added to the
     * world with spawn reason CUSTOM (Paper's spawn events fire; a script's handler runs inside summon{ }). null if
     * the spawn was refused (an event or plugin cancelled it, or the world is on peaceful and the mob is hostile).
     */
    public static org.bukkit.entity.Entity summon(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("summon{}", wire);
        EntityType<?> type = type(a, "summon{}");
        Place at = place(a, "summon{}", slots);
        Entity e = create(type, at, "summon{}");
        List<Runnable> changes = prepare(a, e, slots, false, true);
        a.done();
        ServerLevel level = at.level();
        if (level.getDifficulty() == Difficulty.PEACEFUL && !type.isAllowedInPeaceful()) return null; // as /summon
        if (e instanceof Mob mob) {
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(e.blockPosition()), EntitySpawnReason.COMMAND, null);
        }
        for (Runnable change : changes) change.run();
        if (!level.tryAddFreshEntityWithPassengers(e, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.CUSTOM)) return null;
        return e.isRemoved() ? null : e.getBukkitEntity();
    }

    /**
     * remove_entity{ entity = e }: a real entity is removed the vanilla way (as /kill without dying: no drops, no death
     * event), a clientside entity from every viewer's screen. false if it was already gone. Players: the library
     * refuses them.
     */
    public static boolean remove(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("remove_entity{}", wire);
        Object slotValue = named(a, slots);
        a.done();
        if (slotValue instanceof ClientEntities.Fake fake) return ClientEntities.remove(fake);
        Entity e = entity(slotValue);
        if (e == null || e instanceof ServerPlayer) return false;
        e.discard(org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN);
        return true;
    }

    // ---- chunks (a chunk object is a world plus a chunk position, see blocks.lua) ----

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
