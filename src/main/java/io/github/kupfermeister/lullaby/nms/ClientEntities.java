package io.github.kupfermeister.lullaby.nms;

import io.github.kupfermeister.lullaby.lua.Wire;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.UpdateInterval;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Clientside entities (clientside_summon{ }): entities that exist only in the games of their viewers. The server keeps
 * a real vanilla entity for each ({@link Fake#body}) but never puts it into a world, so it never ticks, never touches
 * blocks or other entities and costs the server nothing per tick. Every Lullaby action works on that body with the
 * same code as on real entities (modify_entity{ }, teleport{ }, the field reads), and this class sends the viewers
 * what changed: vanilla's own spawn packets (ServerEntity.sendPairingData, as when a player first sees an entity),
 * the changed entity data only (SynchedEntityData.packDirty), moves, removal.
 *
 * <p>Viewers are fixed at the summon; offline viewers and viewers in another world get nothing (no error). A
 * player's game forgets the entity by itself when it forgets its chunk (walked away), its world (respawn, world
 * change) or the whole connection (quit); {@code persist} sends it again then. The lifetime (default 10 minutes, -1
 * never) removes it. A reload removes all of them from every screen.
 *
 * <p>Costs: nothing while no clientside entity exists (no listener, an empty check per tick). Each one keeps its
 * vanilla entity in memory (about 1 to 3 KB, guess, not measured). Main thread only (Paper fires the three player
 * events used here on the main thread).
 */
public final class ClientEntities {

    private static final String WHAT = "clientside_summon{}";
    /** The default lifetime: 10 minutes (jft 2026-11-23). */
    private static final int DEFAULT_LIFETIME = 12000;

    /** One clientside entity: what its slot holds. */
    public static final class Fake {
        final Entity body;
        final Set<UUID> viewers;
        final boolean chunks, worlds, rejoin; // persist
        final long end; // the server tick it is removed at; Long.MAX_VALUE: never
        boolean removed;
        ChunkKey indexed; // where CHUNKS keeps it (only with chunks)

        Fake(Entity body, Set<UUID> viewers, boolean[] persist, long end) {
            this.body = body;
            this.viewers = viewers;
            this.chunks = persist[0];
            this.worlds = persist[1];
            this.rejoin = persist[2];
            this.end = end;
        }
    }

    private record ChunkKey(ResourceKey<Level> world, long chunk) {
        static ChunkKey of(Entity e) {
            return new ChunkKey(e.level().dimension(), pack(Mth.floor(e.getX()) >> 4, Mth.floor(e.getZ()) >> 4));
        }

        static long pack(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
    }

    /** Every clientside entity, by entity number. */
    private static final Map<Integer, Fake> ALL = new HashMap<>();
    /** By the tick they end at (then entity number), so the tick check looks only at the first one. */
    private static final TreeSet<Fake> ENDING = new TreeSet<>(
            Comparator.<Fake>comparingLong(f -> f.end).thenComparingInt(f -> f.body.getId()));
    /** persist chunks: viewer -> chunk -> the entities standing there. */
    private static final Map<UUID, Map<ChunkKey, Set<Fake>>> CHUNKS = new HashMap<>();
    /** persist worlds or rejoin: viewer -> entities. */
    private static final Map<UUID, Set<Fake>> VIEWED = new HashMap<>();

    private static Plugin plugin;
    private static final Listener LISTENER = new Listener() {
    };
    private static boolean listening;

    /** Vanilla's ServerEntity wants a way to send to its trackers; Lullaby sends itself, so it gets none. */
    private static final ServerEntity.Synchronizer NO_TRACKERS = new ServerEntity.Synchronizer() {
        @Override
        public void sendToTrackingPlayers(Packet<? super ClientGamePacketListener> packet) {
        }

        @Override
        public void sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener> packet) {
        }

        @Override
        public void sendToTrackingPlayersFiltered(Packet<? super ClientGamePacketListener> packet, Predicate<ServerPlayer> predicate) {
        }
    };

    private ClientEntities() {
    }

    /** Once, when the plugin starts: the listeners need it. */
    public static void plugin(Plugin owner) {
        plugin = owner;
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    /**
     * clientside_summon{ type = , location = , player = | players = , persist = , lifetime = , ...fields }: the
     * library puts a blueprint's fields in and sends the viewers as slots. {@code limit}: the config's
     * clientside_entity_limit (-1: none).
     */
    public static Fake summon(Object wire, IntFunction<Object> slots, int limit) {
        Fields a = new Fields(WHAT, wire);
        EntityType<?> type = Entities.type(a, WHAT);
        Entities.Place at = Entities.place(a, WHAT, slots);
        Set<UUID> viewers = viewers(a, slots);
        boolean[] persist = persist(a);
        int lifetime = a.whole("lifetime", DEFAULT_LIFETIME);
        // FORMAT: a lifetime that is neither -1 nor at least 1 tick
        if (lifetime != -1 && lifetime < 1) throw a.wrong("lifetime", "ticks, at least 1 (-1: forever)", a.raw("lifetime"));
        Entity body = Entities.create(type, at, WHAT);
        List<Runnable> changes = Entities.prepare(a, body, slots, true, true);
        a.done();
        // FORMAT: too many clientside entities (the config's limit)
        if (limit >= 0 && ALL.size() >= limit) throw new IllegalArgumentException(WHAT + ": there are already " + ALL.size()
                + " clientside entities (the config's clientside_entity_limit; remove_entity{ } or a lifetime frees them)");
        for (Runnable change : changes) change.run();
        fireFlag(body);
        long end = lifetime == -1 ? Long.MAX_VALUE : server().getTickCount() + (long) lifetime;
        Fake f = new Fake(body, viewers, persist, end);
        ALL.put(body.getId(), f);
        if (f.end != Long.MAX_VALUE) ENDING.add(f);
        index(f);
        show(f, watching(f));
        forgetChanges(body); // sent with the spawn
        if (f.chunks || f.worlds || f.rejoin) listen();
        return f;
    }

    /** The viewers' UUIDs (Lua sent player = slot or players = { slots }; offline ones are kept for later). */
    private static Set<UUID> viewers(Fields a, IntFunction<Object> slots) {
        Object one = a.raw("player");
        List<Object> list = one != null ? List.of(one) : a.list(a.raw("players"), "players");
        Set<UUID> out = new LinkedHashSet<>(list.size());
        for (Object slot : list) out.add((UUID) slots.apply(((Number) slot).intValue()));
        return out;
    }

    /** persist: missing or false (none), true (all three), or { chunks = , worlds = , rejoin = } (each false if missing). */
    private static boolean[] persist(Fields a) {
        Object v = a.raw("persist");
        if (v == null || Boolean.FALSE.equals(v)) return new boolean[3];
        if (Boolean.TRUE.equals(v)) return new boolean[]{true, true, true};
        if (!(v instanceof Wire.Table)) throw a.wrong("persist", "true, false or { chunks = , worlds = , rejoin = }", v);
        Fields p = a.table("persist");
        boolean[] out = {p.bool("chunks", false), p.bool("worlds", false), p.bool("rejoin", false)};
        p.done();
        return out;
    }

    /** The viewers who are online and in the entity's world now. */
    private static List<ServerPlayer> watching(Fake f) {
        List<ServerPlayer> out = new ArrayList<>(f.viewers.size());
        Level level = f.body.level();
        var players = server().getPlayerList();
        for (UUID uuid : f.viewers) {
            ServerPlayer p = players.getPlayer(uuid);
            if (p != null && p.level() == level) out.add(p);
        }
        return out;
    }

    /** Vanilla's spawn packets for the entity as it is now, built once for all these players. */
    private static void show(Fake f, List<ServerPlayer> to) {
        if (to.isEmpty()) return;
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>();
        new ServerEntity((ServerLevel) f.body.level(), f.body, UpdateInterval.NEVER, false, NO_TRACKERS, new HashSet<>())
                .sendPairingData(to.getFirst(), packets::add);
        Packets.send(to, packets);
    }

    /** The changes already sent (with a spawn): marks them as sent, so the next change doesn't send them again. */
    private static void forgetChanges(Entity body) {
        body.getEntityData().packDirty();
        if (body instanceof LivingEntity living) living.getAttributes().getAttributesToSync().clear();
    }

    /** A body never ticks, so vanilla never sets its burning flag from fire_time: Lullaby does. */
    private static void fireFlag(Entity body) {
        body.setSharedFlagOnFire(body.getRemainingFireTicks() > 0);
    }

    /** After modify_entity{ } changed the body: what changed goes to the viewers (one bundle). */
    static void changed(Fake f, boolean velocity) {
        Entity body = f.body;
        fireFlag(body);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(3);
        var data = body.getEntityData().packDirty();
        if (data != null) packets.add(new ClientboundSetEntityDataPacket(body.getId(), data));
        if (body instanceof LivingEntity living) {
            var attributes = living.getAttributes().getAttributesToSync();
            if (!attributes.isEmpty()) {
                packets.add(new ClientboundUpdateAttributesPacket(body.getId(), attributes));
                attributes.clear();
            }
        }
        if (velocity) packets.add(new ClientboundSetEntityMotionPacket(body));
        Packets.send(watching(f), packets);
    }

    /**
     * teleport{ }: moves the body and its viewers see it move (a display glides over its teleport_duration). Into
     * another world: it leaves the old world's viewers and appears for the viewers in the new one.
     */
    static boolean teleport(Fake f, Entities.Place to) {
        Entity body = f.body;
        if (to.level() != body.level()) {
            Packets.send(watching(f), List.of(new ClientboundRemoveEntitiesPacket(body.getId())));
            body.setLevel(to.level());
            body.snapTo(to.x(), to.y(), to.z(), to.yaw(), to.pitch());
            body.setYHeadRot(to.yaw());
            reindex(f);
            show(f, watching(f));
            return true;
        }
        body.snapTo(to.x(), to.y(), to.z(), to.yaw(), to.pitch());
        body.setYHeadRot(to.yaw());
        reindex(f);
        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(2);
        packets.add(ClientboundTeleportEntityPacket.teleport(body.getId(), PositionMoveRotation.of(body), Set.of(), body.onGround()));
        if (body instanceof LivingEntity) packets.add(new ClientboundRotateHeadPacket(body, Mth.packDegrees(to.yaw())));
        Packets.send(watching(f), packets);
        return true;
    }

    /** remove_entity{ } or the lifetime's end: gone from every viewer's screen; false if it was gone already. */
    static boolean remove(Fake f) {
        if (f.removed) return false;
        f.removed = true;
        ALL.remove(f.body.getId());
        ENDING.remove(f);
        unindex(f);
        List<ServerPlayer> to = new ArrayList<>();
        var players = server().getPlayerList();
        for (UUID uuid : f.viewers) {
            ServerPlayer p = players.getPlayer(uuid);
            if (p != null) to.add(p);
        }
        Packets.send(to, List.of(new ClientboundRemoveEntitiesPacket(f.body.getId())));
        return true;
    }

    /** The viewers as UUID texts, one per line (client_entity.viewers). */
    public static String viewerList(Fake f) {
        StringJoiner out = new StringJoiner("\n");
        for (UUID uuid : f.viewers) out.add(uuid.toString());
        return out.toString();
    }

    /** Each tick: removes the entities whose lifetime ended. Looks only at the first one while none is due. */
    public static void tick() {
        if (ENDING.isEmpty()) return;
        long now = server().getTickCount();
        while (!ENDING.isEmpty() && ENDING.first().end <= now) remove(ENDING.first());
    }

    /** Reload and plugin off: every clientside entity leaves every screen (one packet per player), all is forgotten. */
    public static void clear() {
        if (!ALL.isEmpty()) {
            Map<UUID, List<Integer>> ids = new HashMap<>();
            for (Fake f : ALL.values()) {
                f.removed = true;
                for (UUID uuid : f.viewers) ids.computeIfAbsent(uuid, k -> new ArrayList<>()).add(f.body.getId());
            }
            var players = server().getPlayerList();
            for (var e : ids.entrySet()) {
                ServerPlayer p = players.getPlayer(e.getKey());
                if (p == null) continue;
                int[] list = e.getValue().stream().mapToInt(Integer::intValue).toArray();
                Packets.send(List.of(p), List.of(new ClientboundRemoveEntitiesPacket(list)));
            }
        }
        ALL.clear();
        ENDING.clear();
        CHUNKS.clear();
        VIEWED.clear();
        HandlerList.unregisterAll(LISTENER);
        listening = false;
    }

    // ---- persist: the indexes and the three player events (registered with the first entity that persists) ----

    private static void index(Fake f) {
        if (f.chunks) {
            f.indexed = ChunkKey.of(f.body);
            for (UUID uuid : f.viewers) {
                CHUNKS.computeIfAbsent(uuid, k -> new HashMap<>()).computeIfAbsent(f.indexed, k -> new HashSet<>()).add(f);
            }
        }
        if (f.worlds || f.rejoin) for (UUID uuid : f.viewers) VIEWED.computeIfAbsent(uuid, k -> new HashSet<>()).add(f);
    }

    private static void unindex(Fake f) {
        if (f.indexed != null) {
            for (UUID uuid : f.viewers) {
                Map<ChunkKey, Set<Fake>> chunks = CHUNKS.get(uuid);
                if (chunks == null) continue;
                Set<Fake> here = chunks.get(f.indexed);
                if (here != null && here.remove(f) && here.isEmpty()) chunks.remove(f.indexed);
                if (chunks.isEmpty()) CHUNKS.remove(uuid);
            }
            f.indexed = null;
        }
        if (f.worlds || f.rejoin) {
            for (UUID uuid : f.viewers) {
                Set<Fake> seen = VIEWED.get(uuid);
                if (seen != null && seen.remove(f) && seen.isEmpty()) VIEWED.remove(uuid);
            }
        }
    }

    /** After a move: a new chunk or world moves it in CHUNKS. */
    private static void reindex(Fake f) {
        if (!f.chunks || ChunkKey.of(f.body).equals(f.indexed)) return;
        unindex(f);
        index(f);
    }

    /** Shows these entities again to one player, those in the player's world that {@code which} picks. */
    private static void showAgain(ServerPlayer player, Iterable<Fake> fakes, Predicate<Fake> which) {
        List<ServerPlayer> to = List.of(player);
        for (Fake f : toList(fakes)) { // a copy: show may run other code, never change the set while it is walked
            if (!f.removed && which.test(f) && f.body.level() == player.level()) show(f, to);
        }
    }

    private static List<Fake> toList(Iterable<Fake> fakes) {
        List<Fake> out = new ArrayList<>();
        for (Fake f : fakes) out.add(f);
        return out;
    }

    private static ServerPlayer handle(org.bukkit.entity.Player p) {
        return ((org.bukkit.craftbukkit.entity.CraftPlayer) p).getHandle();
    }

    /**
     * chunks: Paper fires PlayerChunkLoadEvent when a chunk is sent to a player (also again after walking away and
     * back): the entities standing in it are sent again (vanilla's client replaces one it still had). worlds: after a
     * respawn or world change. rejoin: at join. All three at MONITOR, after every other plugin.
     */
    private static void listen() {
        if (listening || plugin == null) return;
        listening = true;
        Bukkit.getPluginManager().registerEvent(PlayerChunkLoadEvent.class, LISTENER, EventPriority.MONITOR, (l, e) -> {
            if (!(e instanceof PlayerChunkLoadEvent load)) return;
            Map<ChunkKey, Set<Fake>> chunks = CHUNKS.get(load.getPlayer().getUniqueId());
            if (chunks == null) return;
            ServerPlayer player = handle(load.getPlayer());
            ChunkKey key = new ChunkKey(player.level().dimension(), ChunkKey.pack(load.getChunk().getX(), load.getChunk().getZ()));
            Set<Fake> here = chunks.get(key);
            if (here != null) showAgain(player, here, f -> true);
        }, plugin);
        Bukkit.getPluginManager().registerEvent(com.destroystokyo.paper.event.player.PlayerPostRespawnEvent.class,
                LISTENER, EventPriority.MONITOR, (l, e) -> {
                    if (e instanceof com.destroystokyo.paper.event.player.PlayerPostRespawnEvent r) worldsAgain(r.getPlayer());
                }, plugin);
        Bukkit.getPluginManager().registerEvent(PlayerChangedWorldEvent.class, LISTENER, EventPriority.MONITOR, (l, e) -> {
            if (e instanceof PlayerChangedWorldEvent c) worldsAgain(c.getPlayer());
        }, plugin);
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, LISTENER, EventPriority.MONITOR, (l, e) -> {
            if (!(e instanceof PlayerJoinEvent join)) return;
            Set<Fake> seen = VIEWED.get(join.getPlayer().getUniqueId());
            if (seen != null) showAgain(handle(join.getPlayer()), seen, f -> f.rejoin);
        }, plugin);
    }

    private static void worldsAgain(org.bukkit.entity.Player p) {
        Set<Fake> seen = VIEWED.get(p.getUniqueId());
        if (seen != null) showAgain(handle(p), seen, f -> f.worlds);
    }
}
