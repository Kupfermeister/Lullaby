package io.github.kupfermeister.lullaby.nms;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.LevelResource;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.persistence.CraftPersistentDataContainer;

/**
 * entity.data and player.data (a player is an entity: the same field), kept INSIDE the entity: one entry
 * {@code "lullaby:data"} in its plugin data (Paper's entity container, which vanilla's entity save writes as
 * {@code BukkitValues}), holding {@code {DataVersion, data}} exactly like {@link ChunkData}. So it travels with the
 * entity: saved with it into the world's entity files, and for a player into the player file
 * ({@code playerdata/<uuid>.dat} of the main world), which is also where an OFFLINE player's data is read and written.
 * The Lua side is data.lua; this class keeps what Java must know between the calls. Lua keeps one table per UUID.
 *
 * <p>When the table goes into the entity (only when it differs from what the entity holds):
 * <ul>
 * <li>entity: when its chunk unloads (EntitiesUnloadEvent: Paper fires it before it writes the entities; data.lua then
 * locks the table, an unloaded entity can't be used) and at every save of the worlds (WorldSaveEvent, before Paper
 * writes them). An entity that died or was otherwise removed for good (EntityRemoveEvent) takes its data with it; Lua
 * locks the table. A trip to another world keeps the UUID and the plugin data, so the table stays.</li>
 * <li>online player: right before Paper saves the player. Paper saves each player on its own timer (player-auto-save
 * in paper-global.yml, a player whose last save is that many ticks ago), late in the tick; {@link #playersDue} finds
 * them at the tick's start (ServerTickStartEvent). Also at quit (PlayerQuitEvent, before Paper saves the player) and at
 * reload and stop. A /save-all saves the players first and then fires the world save: then the data reaches the file
 * with the player's next save.</li>
 * <li>offline player: the table stays (a player object is the same online and offline). At each save a changed table
 * is written into the player file directly (read, change only {@code BukkitValues."lullaby:data"}, write next to it and
 * swap in, the one before stays as {@code .dat_old}, as vanilla saves it). When the player joins, the table is used
 * again if the file still holds what Lullaby wrote or read; if something else changed it, the file wins and data.lua
 * locks the old table.</li>
 * </ul>
 * Data that didn't load cleanly is never written over (that would lose what couldn't be read).
 *
 * <p>Threads: main thread only (the player file write too: only changed offline players, one small file each).
 */
public final class EntityData {

    private static final String KEY = "lullaby:data";
    private static final NamespacedKey BUKKIT_KEY = NamespacedKey.fromString(KEY);
    private static final String BUKKIT_VALUES = "BukkitValues";
    private static final CompoundTag NONE = new CompoundTag(); // "holds no data"

    /** What a read found. */
    public static final int NOT_LOADED = 0, READ = 1, NO_FILE = 2;

    private final SavedData.Reporter reporter;
    private final Runnable firstEntity; // turns the entity listeners on (Engine)
    private final Runnable firstPlayer; // turns the player listeners on (Engine)
    private final Map<UUID, String> names = new HashMap<>(); // every UUID Lua holds a table for -> its name in errors
    private final Set<UUID> players = new HashSet<>(); // those of them that are players
    private final Set<UUID> blocked = new HashSet<>(); // didn't load cleanly: never written
    private final Map<UUID, CompoundTag> offline = new HashMap<>(); // offline players: what their file holds (Lullaby's view)

    public EntityData(SavedData.Reporter reporter, Runnable firstEntity, Runnable firstPlayer) {
        this.reporter = reporter;
        this.firstEntity = firstEntity;
        this.firstPlayer = firstPlayer;
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    private static ServerPlayer online(UUID uuid) {
        return server().getPlayerList().getPlayer(uuid);
    }

    /** The loaded entity with this UUID in any world, or null (players too). */
    private static Entity loaded(UUID uuid) {
        for (ServerLevel level : server().getAllLevels()) {
            Entity e = level.getEntities().get(uuid);
            if (e != null && !e.isRemoved()) return e;
        }
        return null;
    }

    private static CraftPersistentDataContainer container(Entity e) {
        return e.getBukkitEntity().getPersistentDataContainer();
    }

    private static Path playerFile(UUID uuid) {
        return server().storageSource.getLevelPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
    }

    /** The file named in errors: the player file, or the world's entity folder. */
    private String file(UUID uuid) {
        if (players.contains(uuid)) return Nms.shownPath(playerFile(uuid));
        Entity e = loaded(uuid);
        if (e == null) return "";
        return Nms.shownPath(server().storageSource.getDimensionPath(e.level().dimension()).resolve("entities"));
    }

    /** The data's name in errors, as Lua handed it on the read. */
    public String name(UUID uuid) {
        return names.getOrDefault(uuid, "entity(\"" + uuid + "\").data");
    }

    /** Whether Lua holds a table for this UUID (the listeners ask for every entity or player). */
    public boolean has(UUID uuid) {
        return names.containsKey(uuid);
    }

    public boolean hasPlayers() {
        return !players.isEmpty();
    }

    // ---- reading ----

    /**
     * entity.data read: {NOT_LOADED} (no loaded entity with this UUID), {NO_FILE} (an offline player without a player
     * file), else {READ, wire bytes or null (no data)}. {@code name}: the data as scripts write it, for errors.
     */
    public Object[] read(UUID uuid, boolean player, String name, ToIntFunction<Object> addSlot) {
        Tag tag;
        if (player) {
            ServerPlayer p = online(uuid);
            if (p != null) {
                tag = container(p).getTag(KEY);
            } else {
                Path path = playerFile(uuid);
                if (!Files.isRegularFile(path)) return new Object[]{NO_FILE};
                start(uuid, true, name);
                CompoundTag whole;
                try {
                    whole = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                } catch (IOException | RuntimeException e) {
                    // FORMAT: a player file that can't be read
                    return unreadable(uuid, "the player file can't be read (" + e.getMessage() + ")");
                }
                tag = whole.getCompound(BUKKIT_VALUES).map(v -> v.get(KEY)).orElse(null);
                offline.put(uuid, tag instanceof CompoundTag c ? c : NONE);
            }
        } else {
            Entity e = loaded(uuid);
            if (e == null) return new Object[]{NOT_LOADED};
            tag = container(e).getTag(KEY);
        }
        start(uuid, player, name);
        if (tag == null) return new Object[]{READ, null};
        // FORMAT: the entity's data isn't Lullaby's shape at all
        if (!(tag instanceof CompoundTag root)) return unreadable(uuid, "it must be a compound { }");
        List<String> problems = new ArrayList<>();
        ByteBuffer bytes;
        try {
            bytes = SavedData.decodeRoot(root, name, addSlot, problems);
        } catch (SavedData.Unreadable e) {
            return unreadable(uuid, e.getMessage());
        }
        if (!problems.isEmpty()) {
            blocked.add(uuid);
            SavedData.report(reporter, file(uuid), "error", "loading " + name, problems);
        }
        return new Object[]{READ, bytes};
    }

    private void start(UUID uuid, boolean player, String name) {
        // Engine turns each set of listeners on once; running these again costs nothing
        if (player) {
            firstPlayer.run();
            players.add(uuid);
        } else {
            firstEntity.run();
        }
        names.put(uuid, name);
        blocked.remove(uuid);
    }

    private Object[] unreadable(UUID uuid, String why) {
        blocked.add(uuid);
        String name = name(uuid);
        // FORMAT: an entity's data can't be loaded at all
        reporter.report("error", file(uuid), "loading " + name, name + " can't be loaded: " + why + ". It starts"
                + " empty and is not saved until /lullaby reload reads it cleanly");
        return new Object[]{READ, null};
    }

    /** What data.lua couldn't make again (one text per line, "" if none): then the data isn't written over either. */
    public void loaded(UUID uuid, String problems) {
        if (problems.isEmpty()) return;
        blocked.add(uuid);
        SavedData.report(reporter, file(uuid), "error", "loading " + name(uuid), List.of(problems.split("\n")));
    }

    // ---- writing ----

    /**
     * One table (data.lua's save tree) goes where it belongs: into the loaded entity or online player, or into an
     * offline player's file. False when there is no such entity loaded any more: Lua then locks the table.
     */
    public boolean write(UUID uuid, Object tree, IntFunction<Object> slots, String luaProblems) {
        String name = name(uuid);
        boolean player = players.contains(uuid);
        Entity e = player ? online(uuid) : loaded(uuid);
        if (e == null && !player) {
            forget(uuid);
            return false;
        }
        List<String> problems = new ArrayList<>();
        if (!luaProblems.isEmpty()) problems.addAll(List.of(luaProblems.split("\n")));
        CompoundTag root = SavedData.encodeRoot(tree, name, slots, problems);
        if (root != null && root.get("data") instanceof CompoundTag data) {
            ChunkData.fits(data, new SavedData.Place(null, name), problems);
            if (data.isEmpty()) root = null;
        }
        SavedData.report(reporter, file(uuid), "error", "saving " + name, problems);
        if (blocked.contains(uuid)) {
            // FORMAT: an entity's data not saved because it didn't load cleanly
            reporter.report("warning", file(uuid), "saving " + name, name + " is not saved: it did not load cleanly"
                    + " (see the errors from loading), and saving would lose what couldn't be read");
            return true;
        }
        if (e != null) {
            put(container(e), root);
        } else {
            writeFile(uuid, root);
        }
        return true;
    }

    /** An offline player's changed data into the player file (only when it differs from what the file holds). */
    private void writeFile(UUID uuid, CompoundTag root) {
        CompoundTag norm = root == null ? NONE : root;
        if (Objects.equals(offline.get(uuid), norm)) return;
        Path path = playerFile(uuid);
        String name = name(uuid);
        try {
            CompoundTag whole = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
            CompoundTag values = whole.getCompound(BUKKIT_VALUES).orElseGet(CompoundTag::new);
            if (root == null) values.remove(KEY);
            else values.put(KEY, root);
            if (values.isEmpty()) whole.remove(BUKKIT_VALUES);
            else whole.put(BUKKIT_VALUES, values);
            Path fresh = Files.createTempFile(path.getParent(), uuid + "-", ".dat");
            NbtIo.writeCompressed(whole, fresh);
            Util.safeReplaceFile(path, fresh, path.resolveSibling(uuid + ".dat_old"));
            offline.put(uuid, norm);
        } catch (IOException | RuntimeException e) {
            // FORMAT: an offline player's file couldn't be written
            reporter.report("error", Nms.shownPath(path), "saving " + name, name + " could not be written to the"
                    + " player file: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    /** Puts the data into the entity only when it differs. */
    private static void put(CraftPersistentDataContainer container, CompoundTag root) {
        Tag now = container.getTag(KEY);
        if (root == null) {
            if (now != null) container.remove(BUKKIT_KEY);
        } else if (!root.equals(now)) {
            container.put(KEY, root);
        }
    }

    /** Online players whose table Lua holds and whom Paper saves this tick (asked at the tick's start). */
    public List<UUID> playersDue() {
        List<UUID> due = new ArrayList<>();
        int rate = io.papermc.paper.configuration.GlobalConfiguration.get().playerAutoSave.rate;
        if (rate < 0) rate = server().autosavePeriod;
        if (rate <= 0) return due;
        long now = MinecraftServer.currentTick;
        for (ServerPlayer p : server().getPlayerList().getPlayers()) {
            if (now - p.lastSave >= rate && players.contains(p.getUUID())) due.add(p.getUUID());
        }
        return due;
    }

    /** Every UUID Lua holds a table for (a save of everything). */
    public List<UUID> all() {
        return new ArrayList<>(names.keySet());
    }

    /** A player quit: after the table went in at quit, the player file holds that (Paper saves it right after). */
    public void quit(UUID uuid) {
        ServerPlayer p = online(uuid);
        Tag tag = p == null ? null : container(p).getTag(KEY);
        offline.put(uuid, tag instanceof CompoundTag c ? c : NONE);
    }

    /**
     * A player Lua holds a table for joined: true if the player file still holds what Lullaby wrote or read (the table
     * is used again and goes in at the next save), false if something else changed it (the file wins: Lua locks the
     * old table and reads again).
     */
    public boolean joined(UUID uuid) {
        CompoundTag last = offline.remove(uuid);
        if (last == null) return true; // never offline since Lua read it
        ServerPlayer p = online(uuid);
        Tag now = p == null ? null : container(p).getTag(KEY);
        if (last.equals(now instanceof CompoundTag c ? c : NONE)) return true;
        forget(uuid);
        return false;
    }

    /** Lua let go of this table (locked): nothing about it is kept. */
    public void forget(UUID uuid) {
        names.remove(uuid);
        players.remove(uuid);
        blocked.remove(uuid);
        offline.remove(uuid);
    }

    /** A reload (after every table was written): the new Lua state starts from the entities and files again. */
    public void reset() {
        names.clear();
        players.clear();
        blocked.clear();
        offline.clear();
    }
}
