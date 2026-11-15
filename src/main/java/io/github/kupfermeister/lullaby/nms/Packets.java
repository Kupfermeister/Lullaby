package io.github.kupfermeister.lullaby.nms;

import io.github.kupfermeister.lullaby.lua.Wire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntFunction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * Packet actions (particle{}, sound{}, stop_sound{}; Kupfermeister 2026-11-15), hand glue straight on vanilla's packets, and
 * the ONE send core they share ({@link #send}): build each packet once, bundle, hand the same packet objects to every
 * receiver. The generated packet system (on_packet/send_packet) is planned to reuse {@link #send}.
 *
 * <p>Each action arrives as ONE wire table (lazy.lua's I.wire), so a list of 4096 positions crosses in one call. The
 * fields are read here, straight from the wire's picture; Lua only puts the receivers' slot numbers in. Receivers that
 * are offline get nothing (there is no connection to send to). Main thread only.
 */
public final class Packets {

    /** Vanilla's limit of packets in one bundle (BundlerInfo.BUNDLE_SIZE_LIMIT); more would kick the player. */
    private static final int BUNDLE_LIMIT = 4096;
    private static final int VEC3 = 3; // index of "vec3" in the wire's kind list (Codecs.KINDS)

    private static final Map<String, SoundSource> SOURCES = new HashMap<>();
    private static final Map<String, ClientboundLevelParticlesPacket.RandomizationType> RANDOMIZATION = new HashMap<>();

    static {
        for (SoundSource source : SoundSource.values()) SOURCES.put(source.getName(), source);
        for (ClientboundLevelParticlesPacket.RandomizationType type : ClientboundLevelParticlesPacket.RandomizationType.values()) {
            RANDOMIZATION.put(type.name().toLowerCase(Locale.ROOT), type);
        }
    }

    private Packets() {
    }

    // ---- the send core ----

    /**
     * Sends packets to every receiver: one packet as it is, more in bundles of at most 4096 (a bundle is drawn by the
     * client in one frame). Every packet and bundle is built once and shared by all receivers.
     */
    public static void send(List<ServerPlayer> receivers, List<Packet<? super ClientGamePacketListener>> packets) {
        if (receivers.isEmpty() || packets.isEmpty()) return;
        List<Packet<? super ClientGamePacketListener>> out;
        if (packets.size() == 1) {
            out = packets;
        } else {
            out = new ArrayList<>(packets.size() / BUNDLE_LIMIT + 1);
            for (int i = 0; i < packets.size(); i += BUNDLE_LIMIT) {
                List<Packet<? super ClientGamePacketListener>> part = packets.subList(i, Math.min(packets.size(), i + BUNDLE_LIMIT));
                out.add(part.size() == 1 ? part.getFirst() : new ClientboundBundlePacket(new ArrayList<>(part)));
            }
        }
        for (ServerPlayer player : receivers) {
            if (player.hasDisconnected()) continue;
            for (Packet<? super ClientGamePacketListener> packet : out) player.connection.send(packet);
        }
    }

    /** The online players among the slot numbers Lua sent (offline ones have no connection). */
    private static List<ServerPlayer> receivers(Wire.Table a, IntFunction<Object> slots) {
        List<Object> list = list(a.hash.get("players"), "players");
        List<ServerPlayer> result = new ArrayList<>(list.size());
        var playerList = MinecraftServer.getServer().getPlayerList();
        for (Object slot : list) {
            ServerPlayer player = playerList.getPlayer((UUID) slots.apply(((Number) slot).intValue()));
            if (player != null) result.add(player);
        }
        return result;
    }

    // ---- particle{} ----

    /**
     * particle{}: one ClientboundLevelParticlesPacket per position ({@code pos} is a vec3 or a list of vec3).
     * {@code limit}: the config's particle_limit (positions x count, count 0 counts as 1), or 0 for none.
     */
    public static void particle(Object wire, IntFunction<Object> slots, double limit) {
        Wire.Table a = (Wire.Table) wire;
        Object particleField = a.hash.get("particle");
        // FORMAT: missing required field
        if (particleField == null) throw new IllegalArgumentException("particle{}: particle is missing");
        Object posField = a.hash.get("pos");
        if (posField == null) throw new IllegalArgumentException("particle{}: pos is missing (a vec3 or a list of vec3)");
        List<Object> points = posField instanceof Wire.Table t && t.kind == VEC3 ? List.of(t) : list(posField, "pos");

        float[] dist = vec3f(a.hash.get("dist"), "dist");
        float[] speed = vec3f(a.hash.get("max_speed"), "max_speed");
        int count = whole(a.hash.get("count"), "count", 0);
        boolean overrideLimiter = bool(a.hash.get("override_limiter"), "override_limiter");
        boolean alwaysShow = bool(a.hash.get("always_show"), "always_show");
        ClientboundLevelParticlesPacket.RandomizationType randomization = ClientboundLevelParticlesPacket.RandomizationType.DEFAULT;
        Object r = a.hash.get("randomization_type");
        if (r != null) {
            randomization = r instanceof String s ? RANDOMIZATION.get(s) : null;
            // FORMAT: bad randomization_type
            if (randomization == null) throw new IllegalArgumentException("particle{}: randomization_type must be "
                    + "\"default\", \"alternative\" or \"alternative_with_speed\", got " + Codecs.describe(r));
        }
        double total = (double) points.size() * Math.max(count, 1);
        if (limit > 0 && total > limit) {
            // FORMAT: particle limit from the config
            throw new IllegalArgumentException("particle{}: " + Codecs.numberText(total) + " particles (positions x count) "
                    + "is over the config's particle_limit of " + Codecs.numberText(limit));
        }
        ParticleOptions options = Codecs.particle(particleField, slots);
        List<ServerPlayer> to = receivers(a, slots);
        // every point is still checked, but no packets are built for nobody
        if (to.isEmpty()) {
            for (int i = 0; i < points.size(); i++) vec3(points.get(i), points.size() == 1 ? "pos" : "pos[" + (i + 1) + "]");
            return;
        }

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            double[] p = vec3(points.get(i), points.size() == 1 ? "pos" : "pos[" + (i + 1) + "]");
            packets.add(new ClientboundLevelParticlesPacket(options, overrideLimiter, alwaysShow, p[0], p[1], p[2],
                    dist[0], dist[1], dist[2], speed[0], speed[1], speed[2], count, randomization));
        }
        send(to, packets);
    }

    // ---- sound{} and stop_sound{} ----

    /**
     * sound{}: ClientboundSoundPacket at {@code pos}, or ClientboundSoundEntityPacket following {@code entity} (a
     * player for now; offline: nothing is sent). Seed missing: a random one, as vanilla's /playsound.
     */
    public static void sound(Object wire, IntFunction<Object> slots) {
        Wire.Table a = (Wire.Table) wire;
        Holder<SoundEvent> sound = soundEvent(a.hash.get("sound"));
        Object sourceField = a.hash.get("source");
        // FORMAT: missing source
        if (sourceField == null) throw new IllegalArgumentException("sound{}: source is missing (" + sourceNames() + ")");
        SoundSource source = source(sourceField, "sound{}");
        float volume = a.hash.get("volume") == null ? 1 : (float) number(a.hash.get("volume"), "volume");
        float pitch = a.hash.get("pitch") == null ? 1 : (float) number(a.hash.get("pitch"), "pitch");
        Object posField = a.hash.get("pos");
        Object entityField = a.hash.get("entity");
        // FORMAT: pos and entity both or neither
        if ((posField == null) == (entityField == null)) {
            throw new IllegalArgumentException("sound{}: give pos (a vec3) or entity (a player), not both or neither");
        }
        double[] p = posField != null ? vec3(posField, "pos") : null;
        Object seedField = a.hash.get("seed");
        long seed = seedField == null ? 0 : seed(seedField);
        List<ServerPlayer> to = receivers(a, slots);
        if (to.isEmpty()) return;
        if (seedField == null) seed = to.getFirst().level().getRandom().nextLong(); // vanilla: level.getRandom().nextLong()
        Packet<? super ClientGamePacketListener> packet;
        if (p != null) {
            packet = new ClientboundSoundPacket(sound, source, p[0], p[1], p[2], volume, pitch, seed);
        } else {
            Object who = slots.apply(((Number) entityField).intValue());
            ServerPlayer entity = MinecraftServer.getServer().getPlayerList().getPlayer((UUID) who);
            if (entity == null) return;
            packet = new ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed);
        }
        send(to, List.of(packet));
    }

    /** stop_sound{}: ClientboundStopSoundPacket; no sound and no source stops every sound. */
    public static void stopSound(Object wire, IntFunction<Object> slots) {
        Wire.Table a = (Wire.Table) wire;
        Object soundField = a.hash.get("sound");
        Identifier id = null;
        if (soundField != null) id = identifier(soundField, "stop_sound{}: sound");
        Object sourceField = a.hash.get("source");
        SoundSource source = sourceField == null ? null : source(sourceField, "stop_sound{}");
        send(receivers(a, slots), List.of(new ClientboundStopSoundPacket(id, source)));
    }

    /** "entity.player.levelup" (a registered sound by its registry entry; any other id inline, as /playsound) or
     * { sound_id = , range = } (vanilla's inline SoundEvent: range fixed in blocks instead of growing with volume). */
    private static Holder<SoundEvent> soundEvent(Object value) {
        // FORMAT: missing or wrong sound
        if (value == null) throw new IllegalArgumentException("sound{}: sound is missing (an id or { sound_id = , range = })");
        if (value instanceof String) {
            Identifier id = identifier(value, "sound{}: sound");
            var registered = BuiltInRegistries.SOUND_EVENT.get(id);
            if (registered.isPresent()) return registered.get();
            return Holder.direct(SoundEvent.createVariableRangeEvent(id));
        }
        if (value instanceof Wire.Table t && t.kind < 0) {
            Object idField = t.hash.get("sound_id");
            if (idField == null) throw new IllegalArgumentException("sound{}: sound table needs sound_id"); // FORMAT
            Identifier id = identifier(idField, "sound{}: sound_id");
            Object range = t.hash.get("range");
            return Holder.direct(range == null ? SoundEvent.createVariableRangeEvent(id)
                    : SoundEvent.createFixedRangeEvent(id, (float) number(range, "sound range")));
        }
        throw new IllegalArgumentException("sound{}: sound must be an id or { sound_id = , range = }, got " + Codecs.describe(value));
    }

    private static Identifier identifier(Object value, String what) {
        Identifier id = value instanceof String s ? Identifier.tryParse(s) : null;
        // FORMAT: bad id
        if (id == null) throw new IllegalArgumentException(what + " must be an id like \"entity.player.levelup\", got "
                + Codecs.describe(value));
        return id;
    }

    private static SoundSource source(Object value, String what) {
        SoundSource source = value instanceof String s ? SOURCES.get(s) : null;
        // FORMAT: bad sound source
        if (source == null) throw new IllegalArgumentException(what + ": source must be one of " + sourceNames()
                + ", got " + Codecs.describe(value));
        return source;
    }

    private static String sourceNames() {
        List<String> names = new ArrayList<>();
        for (SoundSource source : SoundSource.values()) names.add(source.getName());
        return String.join(", ", names);
    }

    // ---- field readers (each error names the field) ----

    /** A list: t[1..n] with no holes and nothing else (LuaJIT may keep part of a list in its hash part). */
    private static List<Object> list(Object value, String field) {
        if (value instanceof Wire.Table t && t.kind < 0) {
            if (t.hash.isEmpty()) {
                for (int i = 0; i < t.array.size(); i++) {
                    // FORMAT: a list with a hole
                    if (t.array.get(i) == null) throw new IllegalArgumentException(field + " has a hole at [" + (i + 1) + "]");
                }
                return t.array;
            }
            List<Object> sequence = Codecs.sequence(t);
            if (sequence != null) return sequence;
        }
        // FORMAT: not a list
        throw new IllegalArgumentException(field + " must be a list, got " + kindText(value));
    }

    private static double number(Object value, String field) {
        if (value instanceof Double d) return d;
        if (value instanceof Long l) return l;
        // FORMAT: not a number
        throw new IllegalArgumentException(field + " must be a number, got " + kindText(value));
    }

    private static int whole(Object value, String field, int missing) {
        if (value == null) return missing;
        number(value, field);
        try {
            return new LuaOps.Strict((Number) value).intValue();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(field + ": " + e.getMessage());
        }
    }

    /** Any 64-bit whole number (the long64 rule): a Lua number that is whole, or a LuaJIT 64-bit number. */
    private static long seed(Object value) {
        number(value, "seed");
        try {
            return new LuaOps.Strict((Number) value).longValue();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("seed: " + e.getMessage());
        }
    }

    private static boolean bool(Object value, String field) {
        if (value == null) return false;
        if (value instanceof Boolean b) return b;
        // FORMAT: not true/false
        throw new IllegalArgumentException(field + " must be true or false, got " + kindText(value));
    }

    private static double[] vec3(Object value, String field) {
        if (value instanceof Wire.Table t && t.kind == VEC3 && t.array.size() == 3) {
            return new double[]{number(t.array.get(0), field), number(t.array.get(1), field), number(t.array.get(2), field)};
        }
        // FORMAT: not a vec3
        throw new IllegalArgumentException(field + " must be a vec3, got " + kindText(value));
    }

    /** A vec3 as the packet's three floats; missing: 0, 0, 0 (vanilla's packet default). */
    private static float[] vec3f(Object value, String field) {
        if (value == null) return new float[3];
        double[] v = vec3(value, field);
        return new float[]{(float) v[0], (float) v[1], (float) v[2]};
    }

    private static String kindText(Object value) {
        if (value instanceof Wire.Table t) return t.kind >= 0 ? "a " + Codecs.kindName(t.kind) : "a table";
        return Codecs.describe(value == null ? LuaOps.NONE : value);
    }
}
