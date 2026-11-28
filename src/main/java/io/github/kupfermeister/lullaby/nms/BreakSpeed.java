package io.github.kupfermeister.lullaby.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import net.minecraft.core.BlockPos;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * TEST (config {@code custom_hardness}): the groundwork for custom block hardness. A player's game works out how fast
 * it mines a block by itself, from its own copy of the {@code minecraft:block_break_speed} attribute. Here that copy is
 * held at 0 ("can't mine anything") and only gets the player's real value back the moment they start hitting a block;
 * when they stop or give up, it goes back to 0. Later the value sent on "start hitting" will be the ratio between a
 * custom block's hardness and the hardness of the block it looks like, so the game mines it at its real speed.
 *
 * <p>Only the player's game is lied to: the server's own attribute never changes, and the server still checks every
 * break with the real value (so this can't make anything breakable that isn't). Test rule: on a grass block the value
 * stays 0, so grass blocks can't be mined (unless the server sees an instant break, e.g. a fast enough tool).
 * Each change says {@code attribute set to X} in that player's chat.
 *
 * <p>How: one hook per player in the network pipeline, right before vanilla's Connection. Incoming, it watches the
 * "start / stop / abort digging" packets and answers at once on Netty's thread (no wait for the main thread, which
 * could be up to a tick). Outgoing, while the value is held at 0 it rewrites the server's own attribute packets for
 * this player (vanilla sends them when the value changes, e.g. Haste), remembering the real value they carried, and
 * sends 0 again after each respawn (the game makes a new player then). All of a hook's fields are used only on that
 * player's Netty thread (reads and writes of one connection run on its one thread).
 */
public final class BreakSpeed {

    private static final String NAME = "lullaby_break_speed"; // the hook's name in a player's network pipeline
    private static final Listener JOINS = new Listener() { };
    private static boolean on;

    private BreakSpeed() {
    }

    /** Config {@code custom_hardness}: turns the test on or off. Main thread; calling it again changes nothing. */
    public static void configure(Plugin plugin, boolean enable) {
        if (enable == on) return;
        if (!enable) {
            stop();
            return;
        }
        on = true;
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, JOINS, EventPriority.LOWEST, (listener, e) -> {
            if (e instanceof PlayerJoinEvent join) hook(((org.bukkit.craftbukkit.entity.CraftPlayer) join.getPlayer()).getHandle());
        }, plugin);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) hook(player);
    }

    /** Turns it off (config false, plugin stops): every hook leaves and every game gets its real value back. */
    public static void stop() {
        if (!on) return;
        on = false;
        HandlerList.unregisterAll(JOINS);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            Channel channel = player.connection.connection.channel;
            if (channel == null) continue;
            try {
                channel.pipeline().remove(NAME);
            } catch (NoSuchElementException ignored) {
                continue; // never hooked or already closed
            }
            AttributeInstance real = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
            if (real != null) channel.writeAndFlush(new ClientboundUpdateAttributesPacket(player.getId(), List.of(real)));
        }
    }

    private static void hook(ServerPlayer player) {
        Channel channel = player.connection.connection.channel;
        if (channel == null) return; // a player without a real connection (some plugins make such players)
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null || pipeline.get(HandlerNames.PACKET_HANDLER) == null) return;
        AttributeInstance real = player.getAttribute(Attributes.BLOCK_BREAK_SPEED);
        if (real == null) return;
        Hook hook = new Hook(player, copy(real.getBaseValue(), real.getModifiers()));
        try {
            pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, hook);
        } catch (NoSuchElementException | IllegalArgumentException ignored) {
            return; // the connection closed in between
        }
        channel.eventLoop().execute(() -> {
            ChannelHandlerContext ctx = pipeline.context(NAME);
            if (ctx != null) hook.hold(ctx);
        });
    }

    /** A standalone copy of the attribute (not the player's own), for building packets on Netty's thread. */
    private static AttributeInstance copy(double base, Iterable<AttributeModifier> modifiers) {
        AttributeInstance a = new AttributeInstance(Attributes.BLOCK_BREAK_SPEED, x -> { });
        a.setBaseValue(base);
        for (AttributeModifier m : modifiers) a.addTransientModifier(m);
        return a;
    }

    private static AttributeInstance copy(ClientboundUpdateAttributesPacket.AttributeSnapshot s) {
        AttributeInstance a = new AttributeInstance(s.attribute(), x -> { });
        a.setBaseValue(s.base());
        for (AttributeModifier m : s.modifiers()) a.addTransientModifier(m);
        return a;
    }

    private static final class Hook extends ChannelDuplexHandler {
        private final ServerPlayer player;
        private final int id; // the player's entity number (stays the same on respawn)
        private AttributeInstance real; // the player's real value, as the server last sent it
        private boolean held = true; // the game has 0 right now

        Hook(ServerPlayer player, AttributeInstance real) {
            this.player = player;
            this.id = player.getId();
            this.real = real;
        }

        /** Sends 0 (also the first time). ctx: this hook's place, so the packet skips this hook's own write. */
        void hold(ChannelHandlerContext ctx) {
            held = true;
            send(ctx, copy(0, List.of()));
        }

        void release(ChannelHandlerContext ctx) {
            held = false;
            send(ctx, real);
        }

        private void send(ChannelHandlerContext ctx, AttributeInstance value) {
            ctx.write(new ClientboundUpdateAttributesPacket(id, List.of(value)));
            ctx.writeAndFlush(new ClientboundSystemChatPacket(Component.literal("attribute set to " + text(value.getValue())), false));
        }

        private static String text(double v) {
            return v == Math.rint(v) && Math.abs(v) < 1e15 ? Long.toString((long) v) : Double.toString(v);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ServerboundPlayerActionPacket a) {
                switch (a.getAction()) {
                    case START_DESTROY_BLOCK -> {
                        if (!isGrass(a.getPos())) release(ctx);
                        else if (!held) hold(ctx);
                    }
                    case ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                        if (!held) hold(ctx);
                    }
                    default -> { }
                }
            }
            super.channelRead(ctx, msg);
        }

        /**
         * Reads the block on Netty's thread, without loading anything: a block changed in this very moment may read as
         * its old state, which is fine for a test.
         */
        private boolean isGrass(BlockPos pos) {
            LevelChunk chunk = player.level().getChunkIfLoaded(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) return false;
            BlockState state = chunk.getBlockState(pos);
            return state.is(Blocks.GRASS_BLOCK);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ClientboundUpdateAttributesPacket p && p.getEntityId() == id) {
                msg = rewrite(p);
            } else if (msg instanceof ClientboundBundlePacket bundle) {
                List<Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener>> out = new ArrayList<>();
                boolean changed = false;
                for (Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> sub : bundle.subPackets()) {
                    if (sub instanceof ClientboundUpdateAttributesPacket p && p.getEntityId() == id) {
                        Packet<? super net.minecraft.network.protocol.game.ClientGamePacketListener> r = rewrite(p);
                        changed |= r != p;
                        out.add(r);
                    } else {
                        out.add(sub);
                    }
                }
                if (changed) msg = new ClientboundBundlePacket(out);
            }
            super.write(ctx, msg, promise);
            if (msg instanceof ClientboundRespawnPacket && held) {
                ctx.write(new ClientboundUpdateAttributesPacket(id, List.of(copy(0, List.of()))));
            }
        }

        /** Remembers the real value the server sends; while held, the game gets 0 instead. */
        private ClientboundUpdateAttributesPacket rewrite(ClientboundUpdateAttributesPacket p) {
            List<AttributeInstance> values = new ArrayList<>();
            boolean found = false;
            for (ClientboundUpdateAttributesPacket.AttributeSnapshot s : p.getValues()) {
                if (s.attribute().equals(Attributes.BLOCK_BREAK_SPEED)) {
                    found = true;
                    real = copy(s);
                    values.add(held ? copy(0, List.of()) : real);
                } else {
                    values.add(copy(s));
                }
            }
            return found && held ? new ClientboundUpdateAttributesPacket(id, values) : p;
        }
    }
}
