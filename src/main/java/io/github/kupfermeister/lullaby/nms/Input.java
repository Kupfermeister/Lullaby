package io.github.kupfermeister.lullaby.nms;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.StringJoiner;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.HandlerNames;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.network.protocol.game.GamePacketTypes;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket;
import net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

/**
 * Lullaby's own "input" event: the packets a player's game sends when they press a key or click (vanilla's input
 * packets), handed to scripts before vanilla acts on them, one event per packet. The packets, their fields and the
 * words of their action fields are owned here ({@link Kind}, {@link Field}); the library reads them once at startup
 * ({@link #info}), and the input doc block in events.lua explains each one (the docs build checks that both name the
 * same packets, fields and words, see tree-from in tools/docs/Docs.java).
 *
 * <p>How a packet gets to the scripts. Netty (the network library) decodes packets on its own threads and hands them
 * to vanilla's Connection, which queues them for the main thread (vanilla's one queue, PacketProcessor). While a
 * script listens to "input", {@link #HOOK} sits in each player's network pipeline right before the Connection and
 * wraps the input packets in {@link Held}. Vanilla queues a Held like any packet, in the order it came, and the main
 * thread then calls {@link #handle}: the on handlers run, and unless one cancelled, vanilla handles the real packet,
 * then the after handlers run. So scripts see every input in vanilla's own order and timing, and Lua stays on the main
 * thread. A cancelled packet gets what vanilla sends when it refuses that input itself ({@link #refuse}).
 *
 * <p>Threads: {@link #HOOK} runs on Netty's threads and only reads a volatile field and wraps; everything else here
 * runs on the main thread. Without an input handler, no hook is in any pipeline: input costs nothing.
 */
public final class Input {

    /** Runs the scripts' handlers for one packet; returns true if an on handler cancelled it. Main thread. */
    public interface Dispatch {
        boolean run(boolean after, Event event);
    }

    /**
     * Every field a packet can have, in one list (a field's number is its place here). The first seven are the keys
     * of player_input, also the bits of pressed and released (forward = 1, backward = 2, ... sprint = 64).
     */
    enum Field {
        FORWARD, BACKWARD, LEFT, RIGHT, JUMP, SHIFT, SPRINT, PRESSED, RELEASED, ENTITY, HAND, LOCATION,
        USING_SECONDARY_ACTION, Y_ROT, X_ROT, BLOCK, DIRECTION, INSIDE, WORLD_BORDER_HIT, ACTION, DATA, SLOT,
        INCLUDE_DATA, FLYING
    }

    /**
     * The input packets: vanilla's packet type, the fields scripts read (Mojang's names), and for a packet with an
     * action field the vanilla enum and its words. The name is vanilla's packet id; startup checks the names and the
     * words against vanilla (a Minecraft update that renames or adds one stops loading with "Lullaby bug: ...").
     * One packet per line, in this shape: the docs build reads them from this file.
     */
    enum Kind {
        PLAYER_INPUT(GamePacketTypes.SERVERBOUND_PLAYER_INPUT, "forward backward left right jump shift sprint pressed released"),
        PUNCH(GamePacketTypes.SERVERBOUND_PUNCH, ""),
        ATTACK(GamePacketTypes.SERVERBOUND_ATTACK, "entity"),
        INTERACT(GamePacketTypes.SERVERBOUND_INTERACT, "entity hand location using_secondary_action"),
        USE_ITEM(GamePacketTypes.SERVERBOUND_USE_ITEM, "hand y_rot x_rot"),
        USE_ITEM_ON(GamePacketTypes.SERVERBOUND_USE_ITEM_ON, "hand block direction location inside world_border_hit"),
        PLAYER_ACTION(GamePacketTypes.SERVERBOUND_PLAYER_ACTION, "action block direction", ServerboundPlayerActionPacket.Action.class, "start_destroy_block change_destroy_direction abort_destroy_block stop_destroy_block drop_all_items drop_item release_use_item swap_item_with_offhand stab"),
        PLAYER_COMMAND(GamePacketTypes.SERVERBOUND_PLAYER_COMMAND, "action data", ServerboundPlayerCommandPacket.Action.class, "stop_sleeping start_sprinting stop_sprinting start_riding_jump stop_riding_jump open_inventory start_fall_flying"),
        SET_CARRIED_ITEM(GamePacketTypes.SERVERBOUND_SET_CARRIED_ITEM, "slot"),
        PICK_ITEM_FROM_BLOCK(GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_BLOCK, "block include_data"),
        PICK_ITEM_FROM_ENTITY(GamePacketTypes.SERVERBOUND_PICK_ITEM_FROM_ENTITY, "entity include_data"),
        PLAYER_ABILITIES(GamePacketTypes.SERVERBOUND_PLAYER_ABILITIES, "flying");

        final PacketType<?> type;
        final String fields;
        final Class<? extends Enum<?>> actions;
        final String words;

        Kind(PacketType<?> type, String fields) {
            this(type, fields, null, null);
        }

        Kind(PacketType<?> type, String fields, Class<? extends Enum<?>> actions, String words) {
            this.type = type;
            this.fields = fields;
            this.actions = actions;
            this.words = words;
        }
    }

    /** One packet on its way to the scripts: what the player sent, and their keys before it (for pressed/released). */
    public static final class Event {
        final Kind kind;
        final Packet<?> packet;
        final ServerGamePacketListenerImpl connection;
        final ServerPlayer player;
        final net.minecraft.world.entity.player.Input before;

        Event(Kind kind, Packet<?> packet, ServerGamePacketListenerImpl connection) {
            this.kind = kind;
            this.packet = packet;
            this.connection = connection;
            this.player = connection.player;
            this.before = player.getLastClientInput();
        }

        /** The packet's number (its place in {@link Kind}, as {@link #info} lists them). */
        public int kind() {
            return kind.ordinal();
        }

        public UUID player() {
            return player.getUUID();
        }

        /** Why scripts can't cancel this packet, or null if they can (see {@link #refusal(Event)}). */
        public String refusal() {
            return Input.refusal(this);
        }
    }

    private static final String NAME = "lullaby_input"; // the hook's name in a player's network pipeline
    private static final Field[] FIELDS = Field.values();
    private static final Map<PacketType<?>, Kind> KINDS = new IdentityHashMap<>();
    private static final Hook HOOK = new Hook();
    private static final Listener JOINS = new Listener() {
    };

    // Written on the main thread, read by the hook on Netty's threads.
    private static volatile Dispatch target;
    private static volatile boolean after;

    static {
        for (Kind kind : Kind.values()) {
            KINDS.put(kind.type, kind);
            String word = kind.name().toLowerCase(Locale.ROOT);
            // FORMAT: Lullaby's input packet list doesn't match this Minecraft (a Lullaby bug)
            if (!kind.type.id().getPath().equals(word)) throw new IllegalStateException("Lullaby bug: Input.java names "
                    + "the packet " + word + ", this Minecraft calls it " + kind.type.id().getPath());
            for (String f : words(kind.fields)) {
                try {
                    Field.valueOf(f.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("Lullaby bug: Input.java gives " + word + " the field " + f
                            + ", which is not in its Field list");
                }
            }
            if (kind.actions == null) continue;
            List<String> vanilla = new ArrayList<>();
            for (Enum<?> c : kind.actions.getEnumConstants()) vanilla.add(c.name().toLowerCase(Locale.ROOT));
            if (!vanilla.equals(words(kind.words))) throw new IllegalStateException("Lullaby bug: Input.java gives "
                    + word + " the actions " + words(kind.words) + ", this Minecraft has " + vanilla
                    + " (update Input.java and the input doc block in events.lua)");
        }
    }

    private Input() {
    }

    private static List<String> words(String text) {
        return text.isEmpty() ? List.of() : List.of(text.split(" "));
    }

    /**
     * For the library, once at startup: the key names (the bits of pressed and released) on the first line, then one
     * line per packet: its name and its fields as name=number ("punch", "attack entity=9", ...).
     */
    public static String info() {
        StringJoiner out = new StringJoiner("\n");
        StringJoiner keys = new StringJoiner(" ");
        for (int i = 0; i <= Field.SPRINT.ordinal(); i++) keys.add(FIELDS[i].name().toLowerCase(Locale.ROOT));
        out.add(keys.toString());
        for (Kind kind : Kind.values()) {
            StringJoiner line = new StringJoiner(" ");
            line.add(kind.name().toLowerCase(Locale.ROOT));
            for (String f : words(kind.fields)) line.add(f + "=" + Field.valueOf(f.toUpperCase(Locale.ROOT)).ordinal());
            out.add(line.toString());
        }
        return out.toString();
    }

    // ---- turning it on and off (main thread) ------------------------------------------------------------------

    /**
     * Turns the input event on: the hook goes into every online player's pipeline, and into each joining player's.
     * {@code withAfter}: after handlers exist too. Calling it again only adds what is new.
     */
    public static void listen(Plugin plugin, Dispatch dispatch, boolean withAfter) {
        if (withAfter) after = true;
        if (target != null) return;
        target = dispatch;
        Bukkit.getPluginManager().registerEvent(PlayerJoinEvent.class, JOINS, EventPriority.LOWEST, (listener, e) -> {
            if (e instanceof PlayerJoinEvent join) hook(((org.bukkit.craftbukkit.entity.CraftPlayer) join.getPlayer()).getHandle());
        }, plugin);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) hook(player);
    }

    /** Turns it off (before a reload, when the plugin stops): no hook stays in any pipeline. */
    public static void stop() {
        if (target == null) return;
        target = null;
        after = false;
        HandlerList.unregisterAll(JOINS);
        for (ServerPlayer player : MinecraftServer.getServer().getPlayerList().getPlayers()) {
            Channel channel = player.connection.connection.channel;
            if (channel == null) continue;
            try {
                channel.pipeline().remove(NAME);
            } catch (NoSuchElementException ignored) {
                // never hooked (its connection had no packet handler) or already closed
            }
        }
    }

    private static void hook(ServerPlayer player) {
        Channel channel = player.connection.connection.channel;
        if (channel == null) return; // a player without a real connection (some plugins make such players)
        ChannelPipeline pipeline = channel.pipeline();
        if (pipeline.get(NAME) != null || pipeline.get(HandlerNames.PACKET_HANDLER) == null) return;
        try {
            pipeline.addBefore(HandlerNames.PACKET_HANDLER, NAME, HOOK);
        } catch (NoSuchElementException ignored) {
            // the connection closed in between
        }
    }

    /** In each player's pipeline: wraps input packets while scripts listen. Netty's threads: no Lua, no game. */
    @ChannelHandler.Sharable
    private static final class Hook extends ChannelInboundHandlerAdapter {
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (target != null && msg instanceof Packet<?> packet && KINDS.containsKey(packet.type())) {
                msg = new Held((Packet<ServerGamePacketListener>) packet);
            }
            ctx.fireChannelRead(msg);
        }
    }

    /**
     * An input packet with Lullaby in between. Vanilla queues it as it queues the packet itself; on the main thread it
     * runs the scripts, then the packet. Paper's packet limiter counts it for its all-packets limit; a limit set for
     * one packet's class in paper-global.yml doesn't see it (it sees this class).
     */
    private record Held(Packet<ServerGamePacketListener> packet) implements Packet<ServerGamePacketListener> {
        @Override
        public PacketType<? extends Packet<ServerGamePacketListener>> type() {
            return packet.type();
        }

        @Override
        public void handle(ServerGamePacketListener listener) {
            // On Netty's thread this queues it for the main thread and stops here (vanilla's own way, as every handler).
            PacketUtils.ensureRunningOnSameThread(this, listener, MinecraftServer.getServer().packetProcessor());
            Input.handle(packet, (ServerGamePacketListenerImpl) listener);
        }

        @Override
        public boolean isSkippable() {
            return packet.isSkippable();
        }

        @Override
        public boolean isTerminal() {
            return packet.isTerminal();
        }
    }

    /** Main thread, in vanilla's order: on handlers, then vanilla (unless cancelled), then after handlers. */
    private static void handle(Packet<ServerGamePacketListener> packet, ServerGamePacketListenerImpl connection) {
        Dispatch dispatch = target;
        if (dispatch == null) { // turned off while it was queued (a reload)
            packet.handle(connection);
            return;
        }
        Event event = new Event(KINDS.get(packet.type()), packet, connection);
        if (dispatch.run(false, event)) {
            refuse(event);
            return;
        }
        packet.handle(connection);
        if (after && target == dispatch) dispatch.run(true, event);
    }

    // ---- fields (main thread) --------------------------------------------------------------------------------

    /**
     * A field's value for ToLua.java (Bukkit and plain Java values it knows); null reads as nil. pressed and released
     * are bit masks (see {@link Field}). The library only asks for the fields the packet has.
     */
    public static Object read(Event e, int number) {
        Field field = FIELDS[number];
        Packet<?> p = e.packet;
        return switch (field) {
            case FORWARD -> input(e).forward();
            case BACKWARD -> input(e).backward();
            case LEFT -> input(e).left();
            case RIGHT -> input(e).right();
            case JUMP -> input(e).jump();
            case SHIFT -> input(e).shift();
            case SPRINT -> input(e).sprint();
            case PRESSED -> keys(input(e)) & ~keys(e.before);
            case RELEASED -> keys(e.before) & ~keys(input(e));
            case ENTITY -> entity(e, switch (p) {
                case ServerboundAttackPacket a -> a.entityId();
                case ServerboundInteractPacket i -> i.entityId();
                case ServerboundPickItemFromEntityPacket pick -> pick.id();
                default -> throw notIn(e, field);
            });
            case HAND -> word(switch (p) {
                case ServerboundInteractPacket i -> i.hand();
                case ServerboundUseItemPacket u -> u.hand();
                case ServerboundUseItemOnPacket u -> u.hand();
                default -> throw notIn(e, field);
            });
            case LOCATION -> vector(switch (p) {
                case ServerboundInteractPacket i -> i.location();
                case ServerboundUseItemOnPacket u -> u.hitResult().getLocation();
                default -> throw notIn(e, field);
            });
            case USING_SECONDARY_ACTION -> ((ServerboundInteractPacket) p).usingSecondaryAction();
            case Y_ROT -> (double) ((ServerboundUseItemPacket) p).yRot();
            case X_ROT -> (double) ((ServerboundUseItemPacket) p).xRot();
            case BLOCK -> block(e, switch (p) {
                case ServerboundUseItemOnPacket u -> u.hitResult().getBlockPos();
                case ServerboundPlayerActionPacket a -> a.getPos();
                case ServerboundPickItemFromBlockPacket pick -> pick.pos();
                default -> throw notIn(e, field);
            });
            case DIRECTION -> (switch (p) {
                case ServerboundUseItemOnPacket u -> u.hitResult().getDirection();
                case ServerboundPlayerActionPacket a -> a.getDirection();
                default -> throw notIn(e, field);
            }).getSerializedName();
            case INSIDE -> ((ServerboundUseItemOnPacket) p).hitResult().isInside();
            case WORLD_BORDER_HIT -> ((ServerboundUseItemOnPacket) p).hitResult().isWorldBorderHit();
            case ACTION -> word(switch (p) {
                case ServerboundPlayerActionPacket a -> a.getAction();
                case ServerboundPlayerCommandPacket c -> c.getAction();
                default -> throw notIn(e, field);
            });
            case DATA -> ((ServerboundPlayerCommandPacket) p).getData();
            case SLOT -> ((ServerboundSetCarriedItemPacket) p).getSlot();
            case INCLUDE_DATA -> switch (p) {
                case ServerboundPickItemFromBlockPacket pick -> pick.includeData();
                case ServerboundPickItemFromEntityPacket pick -> pick.includeData();
                default -> throw notIn(e, field);
            };
            case FLYING -> ((ServerboundPlayerAbilitiesPacket) p).isFlying();
        };
    }

    private static net.minecraft.world.entity.player.Input input(Event e) {
        return ((ServerboundPlayerInputPacket) e.packet).input();
    }

    private static int keys(net.minecraft.world.entity.player.Input in) {
        return (in.forward() ? 1 : 0) | (in.backward() ? 2 : 0) | (in.left() ? 4 : 0) | (in.right() ? 8 : 0)
                | (in.jump() ? 16 : 0) | (in.shift() ? 32 : 0) | (in.sprint() ? 64 : 0);
    }

    /** The entity the packet names by its network number, as a Bukkit entity for ToLua; null if there is none. */
    private static Object entity(Event e, int id) {
        Entity entity = byNumber(e.player, id);
        return entity == null ? null : entity.getBukkitEntity();
    }

    /**
     * An entity (or a part of the ender dragon) by its network number, exactly as vanilla's handlers of these packets
     * find it. Mojang marks getEntityOrPart deprecated without a replacement; their handlers still use it.
     */
    @SuppressWarnings("deprecation")
    private static Entity byNumber(ServerPlayer player, int id) {
        return player.level().getEntityOrPart(id);
    }

    private static Object block(Event e, BlockPos pos) {
        return e.player.level().getWorld().getBlockAt(pos.getX(), pos.getY(), pos.getZ());
    }

    private static Object vector(Vec3 v) {
        return new org.bukkit.util.Vector(v.x, v.y, v.z);
    }

    private static String word(Enum<?> constant) {
        return constant.name().toLowerCase(Locale.ROOT);
    }

    private static IllegalStateException notIn(Event e, Field field) {
        return new IllegalStateException("Lullaby bug: " + e.kind + " has no field " + field);
    }

    // ---- cancelling (main thread) ----------------------------------------------------------------------------

    /**
     * Why this packet can't be cancelled, or null if it can. Scripts can cancel what vanilla itself may refuse; what
     * vanilla always accepts (the player's game has already acted on it) can't be undone.
     */
    static String refusal(Event e) {
        // FORMAT: an input packet that can't be cancelled
        return switch (e.packet) {
            case ServerboundPlayerInputPacket ignored -> "player_input can't be cancelled: the player's game already "
                    + "moves by its keys (vanilla never refuses them)";
            case ServerboundPlayerActionPacket a when a.getAction() == ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM ->
                    "release_use_item can't be cancelled: the player's game already stopped using the item (vanilla "
                            + "never refuses it)";
            case ServerboundPlayerCommandPacket c when c.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING
                    || c.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING ->
                    word(c.getAction()) + " can't be cancelled: the player's game already sprints by itself (vanilla "
                            + "never refuses it)";
            default -> null;
        };
    }

    /**
     * A cancelled packet: vanilla never sees it, and the player's game gets what vanilla sends when it refuses that
     * input itself, so nothing it already guessed stays wrong on its screen (a block, the hotbar, flying).
     */
    private static void refuse(Event e) {
        ServerGamePacketListenerImpl c = e.connection;
        ServerPlayer player = e.player;
        switch (e.packet) {
            case ServerboundInteractPacket i -> {
                player.containerMenu.sendAllDataToRemote();
                Entity target = byNumber(player, i.entityId());
                if (target != null) target.refreshEntityData(player);
            }
            case ServerboundUseItemPacket u -> {
                c.ackBlockChangesUpTo(u.sequence());
                player.containerMenu.sendAllDataToRemote();
            }
            case ServerboundUseItemOnPacket u -> {
                c.ackBlockChangesUpTo(u.sequence());
                BlockPos pos = u.hitResult().getBlockPos();
                c.send(new ClientboundBlockUpdatePacket(player.level(), pos));
                c.send(new ClientboundBlockUpdatePacket(player.level(), pos.relative(u.hitResult().getDirection())));
                player.containerMenu.sendAllDataToRemote();
            }
            case ServerboundPlayerActionPacket a -> {
                switch (a.getAction()) {
                    case START_DESTROY_BLOCK, CHANGE_DESTROY_DIRECTION, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK -> {
                        c.send(new ClientboundBlockUpdatePacket(player.level(), a.getPos()));
                        c.ackBlockChangesUpTo(a.getSequence());
                    }
                    case DROP_ITEM, DROP_ALL_ITEMS -> player.containerMenu.sendAllDataToRemote();
                    default -> {
                        // swap_item_with_offhand, stab: the player's game waits for the server
                    }
                }
            }
            case ServerboundPlayerCommandPacket cmd when cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_FALL_FLYING ->
                    player.stopFallFlying();
            case ServerboundSetCarriedItemPacket ignored ->
                    c.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
            case ServerboundPlayerAbilitiesPacket ignored -> player.onUpdateAbilities();
            default -> {
                // punch, attack, pick_item_*, the other commands: the player's game waits for the server
            }
        }
    }
}
