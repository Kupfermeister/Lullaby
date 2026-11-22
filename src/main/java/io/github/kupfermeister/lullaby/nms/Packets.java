package io.github.kupfermeister.lullaby.nms;

import io.github.kupfermeister.lullaby.lua.Wire;
import java.util.ArrayList;
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
 * Packet actions (particle{}, sound{}, stop_sound{}; ), hand glue straight on vanilla's packets, and
 * the ONE send core they share ({@link #send}): build each packet once, bundle, hand the same packet objects to every
 * receiver. The generated packet system (on_packet/send_packet) is planned to reuse {@link #send}.
 *
 * <p>Each action arrives as ONE wire table (lazy.lua's I.wire), so a list of 4096 positions crosses in one call. The
 * fields are read and checked through {@link Fields} (the one place for that); Lua only puts the receivers' slot
 * numbers in and checks the config's particle_limit before packing a huge list. Receivers that
 * are offline get nothing (there is no connection to send to). Main thread only.
 */
public final class Packets {

    /** Vanilla's limit of packets in one bundle (BundlerInfo.BUNDLE_SIZE_LIMIT); more would kick the player. */
    private static final int BUNDLE_LIMIT = 4096;
    // Names straight from vanilla's own enums (their owner), so errors and checks can't drift from the game.
    private static final Map<String, SoundSource> SOURCES = Fields.byName(SoundSource::getName, SoundSource.values());
    private static final Map<String, ClientboundLevelParticlesPacket.RandomizationType> RANDOMIZATION = Fields.byName(
            type -> type.name().toLowerCase(Locale.ROOT), ClientboundLevelParticlesPacket.RandomizationType.values());

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

    /**
     * The online players among the slot numbers Lua sent (offline ones have no connection): player = slot or
     * players = { slots } (Lua checked that exactly one is there and that they are players).
     */
    private static List<ServerPlayer> receivers(Fields a, IntFunction<Object> slots) {
        Object one = a.raw("player");
        Object many = a.raw("players");
        List<Object> list = one != null ? List.of(one) : a.list(many, "players");
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
     * particle{}: one ClientboundLevelParticlesPacket per position ({@code pos} is a vec3 or a list of vec3). The
     * config's particle_limit is checked in Lua, before the list is packed (a huge list would cost memory first).
     */
    public static void particle(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("particle{}", wire);
        Object particleField = a.required("particle", "text like \"flame\" or a table { type = \"dust\", ... }");
        List<Object> points = a.vec3s("pos", "a vec3 or a list of vec3");
        float[] dist = a.vec3f("dist");
        float[] speed = a.vec3f("max_speed");
        int count = a.whole("count", 0);
        boolean overrideLimiter = a.bool("override_limiter", false);
        boolean alwaysShow = a.bool("always_show", false);
        var randomization = a.choice("randomization_type", RANDOMIZATION,
                ClientboundLevelParticlesPacket.RandomizationType.DEFAULT);
        List<ServerPlayer> to = receivers(a, slots);
        a.done();
        ParticleOptions options;
        try {
            options = Codecs.particle(particleField, slots);
        } catch (IllegalArgumentException e) {
            // FORMAT: the action and the field in front of the codec's own message, like every Fields error
            String why = e.getMessage();
            throw new IllegalArgumentException("particle{}: " + (why.startsWith("particle") ? why : "particle: " + why), e);
        }
        // every point is still checked, but no packets are built for nobody
        if (to.isEmpty()) {
            for (int i = 0; i < points.size(); i++) a.vec3(points.get(i), points.size() == 1 ? "pos" : "pos[" + (i + 1) + "]");
            return;
        }

        List<Packet<? super ClientGamePacketListener>> packets = new ArrayList<>(points.size());
        for (int i = 0; i < points.size(); i++) {
            double[] p = a.vec3(points.get(i), points.size() == 1 ? "pos" : "pos[" + (i + 1) + "]");
            packets.add(new ClientboundLevelParticlesPacket(options, overrideLimiter, alwaysShow, p[0], p[1], p[2],
                    dist[0], dist[1], dist[2], speed[0], speed[1], speed[2], count, randomization));
        }
        send(to, packets);
    }

    // ---- sound{} and stop_sound{} ----

    /**
     * sound{}: ClientboundSoundPacket at {@code pos}, or ClientboundSoundEntityPacket following {@code entity} (any
     * entity, players too; gone or offline: nothing is sent). Seed missing: a random one, as vanilla's /playsound.
     */
    public static void sound(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("sound{}", wire);
        Holder<SoundEvent> sound = soundEvent(a);
        SoundSource source = a.choice("source", SOURCES, null);
        float volume = (float) a.number("volume", 1);
        float pitch = (float) a.number("pitch", 1);
        // FORMAT: pos and entity both or neither
        if (a.has("pos") == a.has("entity")) {
            throw new IllegalArgumentException("sound{}: give pos (a vec3) or entity (an entity), not both or neither");
        }
        double[] p = a.has("pos") ? a.vec3("pos") : null;
        long seed = a.long64("seed", 0);
        List<ServerPlayer> to = receivers(a, slots);
        a.done();
        if (to.isEmpty()) return;
        if (!a.has("seed")) seed = to.getFirst().level().getRandom().nextLong(); // vanilla: level.getRandom().nextLong()
        Packet<? super ClientGamePacketListener> packet;
        if (p != null) {
            packet = new ClientboundSoundPacket(sound, source, p[0], p[1], p[2], volume, pitch, seed);
        } else {
            net.minecraft.world.entity.Entity entity = Entities.entity(slots.apply(((Number) a.raw("entity")).intValue()));
            if (entity == null) return;
            packet = new ClientboundSoundEntityPacket(sound, source, entity, volume, pitch, seed);
        }
        send(to, List.of(packet));
    }

    /** stop_sound{}: ClientboundStopSoundPacket; no sound and no source stops every sound. */
    public static void stopSound(Object wire, IntFunction<Object> slots) {
        Fields a = new Fields("stop_sound{}", wire);
        Identifier id = a.has("sound") ? identifier(a, "sound", a.raw("sound")) : null;
        SoundSource source = a.has("source") ? a.choice("source", SOURCES, null) : null;
        List<ServerPlayer> to = receivers(a, slots);
        a.done();
        send(to, List.of(new ClientboundStopSoundPacket(id, source)));
    }

    /** "entity.player.levelup" (a registered sound by its registry entry; any other id inline, as /playsound) or
     * { sound_id = , range = } (vanilla's inline SoundEvent: range fixed in blocks instead of growing with volume). */
    private static Holder<SoundEvent> soundEvent(Fields a) {
        Object value = a.required("sound", "an id or { sound_id = , range = }");
        if (value instanceof String) {
            Identifier id = identifier(a, "sound", value);
            var registered = BuiltInRegistries.SOUND_EVENT.get(id);
            if (registered.isPresent()) return registered.get();
            return Holder.direct(SoundEvent.createVariableRangeEvent(id));
        }
        if (value instanceof Wire.Table t && t.kind < 0) {
            Fields inner = new Fields("sound{}: sound", t);
            Identifier id = identifier(inner, "sound_id", inner.required("sound_id", "an id like \"entity.player.levelup\""));
            boolean fixed = inner.has("range");
            float range = (float) inner.number("range", 0);
            inner.done();
            return Holder.direct(fixed ? SoundEvent.createFixedRangeEvent(id, range) : SoundEvent.createVariableRangeEvent(id));
        }
        throw a.wrong("sound", "an id or { sound_id = , range = }", value);
    }

    private static Identifier identifier(Fields a, String field, Object value) {
        Identifier id = value instanceof String s ? Identifier.tryParse(s) : null;
        if (id == null) throw a.wrong(field, "an id like \"entity.player.levelup\"", value);
        return id;
    }
}
