package io.github.kupfermeister.lullaby.nms;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSoundEntityPacket;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * The packets of sound mosaicing (mosaic/): {@link #prepare} builds every packet of a mosaic on the main thread
 * (Sound Entity packets that follow the listening player, Stop Sound packets), {@link #send} hands one to the
 * player's connection and may be called from the mosaic's playback thread: it touches no world object, only
 * {@code connection.send}, which Netty makes safe from any thread. Part of the removable mosaic feature (see
 * mosaic/package-info.java).
 */
public final class MosaicSender {

    private MosaicSender() {
    }

    /** One timed step: play (sound id, source, volume, pitch, seed) or stop (sound id, source). */
    public record Step(int ms, boolean stop, String sound, String source, float volume, float pitch, long seed) {
    }

    /** The built packets for one player. */
    public static final class Prepared {
        private final ServerPlayer player;
        private final Packet<? super ClientGamePacketListener>[] packets;

        private Prepared(ServerPlayer player, Packet<? super ClientGamePacketListener>[] packets) {
            this.player = player;
            this.packets = packets;
        }
    }

    private static final Map<String, SoundSource> SOURCES = Fields.byName(SoundSource::getName, SoundSource.values());

    /** Main thread. null if the player is offline. */
    @SuppressWarnings("unchecked")
    public static Prepared prepare(UUID player, List<Step> steps) {
        ServerPlayer p = MinecraftServer.getServer().getPlayerList().getPlayer(player);
        if (p == null) return null;
        Packet<? super ClientGamePacketListener>[] packets = new Packet[steps.size()];
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            Identifier id = Identifier.withDefaultNamespace(s.sound());
            SoundSource source = SOURCES.get(s.source());
            if (s.stop()) {
                packets[i] = new ClientboundStopSoundPacket(id, source);
            } else {
                Holder<SoundEvent> sound = BuiltInRegistries.SOUND_EVENT.get(id).<Holder<SoundEvent>>map(h -> h)
                        .orElseGet(() -> Holder.direct(SoundEvent.createVariableRangeEvent(id)));
                packets[i] = new ClientboundSoundEntityPacket(sound, source, p, s.volume(), s.pitch(), s.seed());
            }
        }
        return new Prepared(p, packets);
    }

    /** Any thread. false once the player left (then nothing more is sent). */
    public static boolean send(Prepared prepared, int index) {
        if (prepared.player.hasDisconnected()) return false;
        prepared.player.connection.send(prepared.packets[index]);
        return true;
    }

    /** Any thread: stops every sound the player hears. */
    public static void stopAll(Prepared prepared) {
        if (!prepared.player.hasDisconnected()) prepared.player.connection.send(new ClientboundStopSoundPacket(null, null));
    }
}
