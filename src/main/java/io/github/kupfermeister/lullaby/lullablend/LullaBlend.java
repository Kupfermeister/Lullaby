package io.github.kupfermeister.lullaby.lullablend;

import io.github.kupfermeister.lullaby.nms.BlockEdit;
import io.github.kupfermeister.lullaby.nms.BlockEngine;
import io.github.kupfermeister.lullaby.nms.Nms;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * LullaBlend, the server side: Blender's scenes become block edits for the block engine ({@link BlockEngine}).
 * Each player has one session: a token (Blender's password for that player), an origin (where Blender's origin
 * lands, and which way Blender's north faces), a mode, viewers, and the last scene. A new scene is built into a
 * block edit on the block worker thread and shown as fake blocks; {@code place} writes it for real and keeps an
 * undo file.
 *
 * <p>Lives as long as the plugin (reloads don't touch it); the config's {@code lullablend_port} starts or stops the
 * link. Main thread, except what {@link LinkServer} and the block worker do (they never touch sessions).
 */
public final class LullaBlend {

    /** One 16x16x16 piece of a Blender scene: its palette of state texts and 4096 cells (0: nothing, k: palette[k-1]). */
    record BlenderSection(int x, int y, int z, String[] palette, short[] cells) {
    }

    enum Mode {
        MESH("only the build's own blocks"), BOX("the build's box is emptied first"),
        CHUNKS("every chunk the build touches is emptied first, bedrock to sky");

        final String text;

        Mode(String text) {
            this.text = text;
        }
    }

    static final class Session {
        final UUID player;
        String world; // origin
        int x, y, z, turns; // turns: quarter turns clockwise (0 north, 1 east, 2 south, 3 west)
        boolean hasOrigin;
        Mode mode = Mode.MESH;
        final Set<UUID> viewers = new LinkedHashSet<>();
        boolean showing = true;
        Map<Long, BlenderSection> scene = Map.of();
        LinkServer.Link link;
        BlockEngine.Layer layer;
        BlockEdit edit;
        boolean building, again;
        BlockEngine.Job writing;
        String lastProblem;

        Session(UUID player) {
            this.player = player;
            viewers.add(player);
        }
    }

    private static LullaBlend instance;

    private final Plugin plugin;
    private final Path folder;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, UUID> tokens = new ConcurrentHashMap<>(); // SHA-256 of the token (hex) -> player
    private final ConcurrentLinkedQueue<Runnable> inbox = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> stateCache = new ConcurrentHashMap<>(); // "text|turns" -> state, -1 unknown
    private LinkServer server;
    private String serverConfig = ""; // "bind:port" the link runs on, "" off

    private LullaBlend(Plugin plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataPath().resolve("lullablend");
        loadTokens();
    }

    public static void start(Plugin plugin) {
        instance = new LullaBlend(plugin);
    }

    public static LullaBlend get() {
        return instance;
    }

    public static void stop() {
        if (instance == null) return;
        if (instance.server != null) instance.server.close();
        instance = null;
    }

    /** From the config at each load: port (0: off) and the address to listen on. */
    public static String configure(int port, String bind) {
        LullaBlend l = instance;
        if (l == null) return null;
        String want = port <= 0 ? "" : bind + ":" + port;
        if (want.equals(l.serverConfig)) return null;
        if (l.server != null) {
            l.server.close();
            l.server = null;
        }
        l.serverConfig = "";
        if (port <= 0) return null;
        try {
            l.server = new LinkServer(l, bind, port);
            l.serverConfig = want;
            l.log("LullaBlend link listening on " + want); // FORMAT
            return null;
        } catch (IOException e) {
            // FORMAT: the link's port can't be opened
            return "LullaBlend can't listen on " + want + ": " + e.getMessage();
        }
    }

    void log(String text) {
        plugin.getComponentLogger().info(text);
    }

    /** From the link threads: run on the main thread at the next tick. */
    void post(Runnable r) {
        inbox.add(r);
    }

    /** Once per tick, main thread. */
    public static void tick() {
        LullaBlend l = instance;
        if (l == null) return;
        Runnable r;
        while ((r = l.inbox.poll()) != null) r.run();
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) << 42 | ((long) z & 0x3FFFFF) << 20 | ((long) y & 0xFFFFF);
    }

    Session session(UUID player) {
        return sessions.computeIfAbsent(player, Session::new);
    }

    // ---- tokens ----

    private Path tokenFile() {
        return folder.resolve("tokens.txt");
    }

    private void loadTokens() {
        try {
            if (!Files.exists(tokenFile())) return;
            for (String line : Files.readAllLines(tokenFile())) {
                String[] p = line.trim().split(" ");
                if (p.length == 2) tokens.put(p[1], UUID.fromString(p[0]));
            }
        } catch (IOException | IllegalArgumentException e) {
            log("LullaBlend: tokens.txt can't be read: " + e.getMessage()); // FORMAT
        }
    }

    private void saveTokens() {
        try {
            Files.createDirectories(folder);
            List<String> lines = new ArrayList<>();
            // FORMAT: tokens.txt, one "player-uuid sha256-of-token" per line (the token itself is never saved)
            tokens.forEach((hash, id) -> lines.add(id + " " + hash));
            Files.write(tokenFile(), lines);
        } catch (IOException e) {
            log("LullaBlend: tokens.txt can't be written: " + e.getMessage()); // FORMAT
        }
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A new token for the player; the old one stops working (also kicks an open link). */
    String newToken(UUID player) {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.values().removeIf(player::equals);
        tokens.put(hash(token), player);
        saveTokens();
        Session s = sessions.get(player);
        if (s != null && s.link != null) {
            s.link.sendError("a new token was made for this player; paste it into Blender"); // FORMAT
            s.link = null;
        }
        return token;
    }

    // ---- link events (main thread) ----

    void hello(LinkServer.Link link, int protocol, String token, String pack) {
        UUID player = tokens.get(hash(token));
        if (protocol != LinkServer.PROTOCOL) {
            // FORMAT: protocol mismatch
            link.sendError("this server's Lullaby speaks LullaBlend protocol " + LinkServer.PROTOCOL + ", the add-on "
                    + protocol + ": update the older one");
            return;
        }
        if (player == null) {
            link.sendError("unknown token: make one in the game with /lullaby blenderbridge token"); // FORMAT
            return;
        }
        if (!allowed(player)) {
            link.sendError("this player may not use LullaBlend (permission lullaby.blenderbridge)"); // FORMAT
            return;
        }
        Session s = session(player);
        if (s.link != null) s.link.sendError("another Blender connected for this player"); // FORMAT
        s.link = link;
        link.session = s;
        String version = Nms.minecraftVersion();
        link.sendWelcome(name(player), version);
        if (!pack.equals(version)) {
            // FORMAT: Blender's block pack is for another Minecraft version
            link.sendStatus("warning: Blender's block pack is for " + pack + ", the server runs " + version
                    + "; blocks that don't exist here are skipped");
        }
        status(s, s.hasOrigin ? "connected; origin " + originText(s) : "connected; set the origin in the game with /lullaby blenderbridge origin"); // FORMAT
        log("LullaBlend: " + name(player) + " connected from " + link.address()); // FORMAT
    }

    void gone(LinkServer.Link link) {
        Session s = link.session;
        if (s != null && s.link == link) s.link = null;
    }

    void scene(LinkServer.Link link, Map<Long, BlenderSection> scene) {
        Session s = link.session;
        if (s == null || s.link != link) return;
        s.scene = scene;
        rebuild(s);
    }

    void command(LinkServer.Link link, String command) {
        Session s = link.session;
        if (s == null || s.link != link) return;
        if (!allowed(s.player)) {
            status(s, "this player may not use LullaBlend now"); // FORMAT
            return;
        }
        switch (command) {
            case "place" -> place(s, text -> status(s, text));
            case "undo" -> undo(s, text -> status(s, text));
            case "show" -> setShowing(s, true);
            case "hide" -> setShowing(s, false);
            default -> status(s, "unknown command " + command); // FORMAT
        }
    }

    private static boolean allowed(UUID player) {
        Player p = Bukkit.getPlayer(player);
        if (p != null) return p.hasPermission("lullaby.blenderbridge");
        OfflinePlayer o = Bukkit.getOfflinePlayer(player);
        return o.isOp();
    }

    static String name(UUID player) {
        String n = Bukkit.getOfflinePlayer(player).getName();
        return n == null ? player.toString() : n;
    }

    void status(Session s, String text) {
        if (s.link != null) s.link.sendStatus(text);
    }

    /** Tells the player (if online) and Blender. */
    void tell(Session s, String text) {
        status(s, text);
        Player p = Bukkit.getPlayer(s.player);
        // FORMAT: LullaBlend message in chat
        Component line = Component.text("LullaBlend: ", NamedTextColor.LIGHT_PURPLE).append(Component.text(text, NamedTextColor.GRAY));
        if (p != null) p.sendMessage(line);
        // the console or an admin (/lullaby blenderbridge as ...) who typed the command hears it too
        if (echo != null && echo != p) echo.sendMessage(line);
    }

    /** Who typed the command being run right now (set by LullaBlendCommand around each command), or null. */
    org.bukkit.command.CommandSender echo;

    // ---- origin, mode, viewers ----

    static String originText(Session s) {
        return s.x + " " + s.y + " " + s.z + " in " + s.world + ", Blender's north faces "
                + new String[]{"north", "east", "south", "west"}[s.turns];
    }

    void setOrigin(Session s, World world, int x, int y, int z, int turns) {
        s.world = world.getKey().toString();
        s.x = x;
        s.y = y;
        s.z = z;
        s.turns = Math.floorMod(turns, 4);
        s.hasOrigin = true;
        tell(s, "origin " + originText(s)); // FORMAT
        rebuild(s);
    }

    void setMode(Session s, Mode mode) {
        s.mode = mode;
        tell(s, "mode " + mode.name().toLowerCase() + ": " + mode.text); // FORMAT
        rebuild(s);
    }

    void setShowing(Session s, boolean on) {
        s.showing = on;
        if (!on && s.layer != null) {
            BlockEngine.hide(s.layer);
            s.layer = null;
        }
        tell(s, on ? "preview on" : "preview off (the real blocks show again)"); // FORMAT
        if (on) rebuild(s);
    }

    void setViewers(Session s, Set<UUID> viewers) {
        s.viewers.clear();
        s.viewers.addAll(viewers);
        if (s.layer != null) BlockEngine.setViewers(s.layer, s.viewers);
    }

    // ---- building ----

    /** Builds the scene into a block edit on the worker (one build at a time; the newest scene always wins). */
    void rebuild(Session s) {
        if (!s.hasOrigin) return;
        if (s.building) {
            s.again = true;
            return;
        }
        World world = Bukkit.getWorld(org.bukkit.NamespacedKey.fromString(s.world));
        if (world == null) {
            tell(s, "the origin's world " + s.world + " isn't loaded"); // FORMAT
            return;
        }
        s.building = true;
        Map<Long, BlenderSection> scene = s.scene;
        String w = s.world;
        int ox = s.x, oy = s.y, oz = s.z, turns = s.turns, minY = world.getMinHeight(), maxY = world.getMaxHeight() - 1;
        Mode mode = s.mode;
        BlockEngine.work(() -> build(scene, w, ox, oy, oz, turns, mode, minY, maxY), result -> {
            s.building = false;
            s.edit = result.edit;
            if (result.unknown > 0) {
                // FORMAT: blocks this server doesn't know
                s.lastProblem = result.unknown + " blocks skipped, unknown here: " + String.join(", ", result.unknownNames);
                status(s, s.lastProblem);
            }
            if (s.showing) {
                if (s.layer == null) s.layer = BlockEngine.show(s.edit, s.viewers);
                else BlockEngine.update(s.layer, s.edit);
            }
            // FORMAT: preview status
            status(s, "preview: " + s.edit.blocks() + " blocks in " + s.edit.sectionCount() + " sections");
            if (s.again) {
                s.again = false;
                rebuild(s);
            }
        }, error -> {
            s.building = false;
            tell(s, "building the preview failed: " + error); // FORMAT
            plugin.getSLF4JLogger().error("LullaBlend build", error);
        });
    }

    private record Built(BlockEdit edit, long unknown, List<String> unknownNames) {
    }

    /** Block worker thread: Blender's sections, turned and moved to the origin, as one block edit. */
    private Built build(Map<Long, BlenderSection> scene, String world, int ox, int oy, int oz, int turns, Mode mode,
                        int minY, int maxY) {
        BlockEdit.Builder b = new BlockEdit.Builder(BlockEdit.worldKey(world));
        long unknown = 0;
        Set<String> unknownNames = new LinkedHashSet<>();
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlenderSection sec : scene.values()) {
            int[] ids = new int[sec.palette().length];
            for (int k = 0; k < ids.length; k++) {
                String text = sec.palette()[k];
                ids[k] = stateCache.computeIfAbsent(text + "|" + turns, t -> {
                    try {
                        return BlockEdit.turn(Nms.blockStateParse(text), turns, false);
                    } catch (RuntimeException e) {
                        return -1;
                    }
                });
            }
            for (int i = 0; i < 4096; i++) {
                int c = sec.cells()[i];
                if (c == 0) continue;
                int id = ids[c - 1];
                if (id == -1) {
                    unknown++;
                    if (unknownNames.size() < 5) unknownNames.add(sec.palette()[c - 1]);
                    continue;
                }
                int rx = (sec.x() << 4) + (i & 15), ry = (sec.y() << 4) + (i >> 8), rz = (sec.z() << 4) + ((i >> 4) & 15);
                // quarter turns clockwise seen from above: (x, z) -> (-z, x)
                for (int t = 0; t < turns; t++) {
                    int tx = -rz;
                    rz = rx;
                    rx = tx;
                }
                int wx = ox + rx, wy = oy + ry, wz = oz + rz;
                if (wy < minY || wy > maxY) continue;
                b.put(wx, wy, wz, id);
                x0 = Math.min(x0, wx); y0 = Math.min(y0, wy); z0 = Math.min(z0, wz);
                x1 = Math.max(x1, wx); y1 = Math.max(y1, wy); z1 = Math.max(z1, wz);
            }
        }
        if (x0 <= x1) {
            int air = BlockEdit.air();
            if (mode == Mode.BOX) b.fillUntouched(x0, y0, z0, x1, y1, z1, air);
            if (mode == Mode.CHUNKS) {
                for (long c : b.chunkColumns()) {
                    int cx = (int) (c >> 32), cz = (int) c;
                    b.fillUntouched(cx << 4, minY, cz << 4, (cx << 4) + 15, maxY, (cz << 4) + 15, air);
                }
            }
        }
        return new Built(b.build(), unknown, new ArrayList<>(unknownNames));
    }

    // ---- placing and undo ----

    private Path undoFolder() {
        return folder.resolve("undo");
    }

    void place(Session s, java.util.function.Consumer<String> say) {
        if (s.writing != null) {
            say.accept("still writing the last place"); // FORMAT
            return;
        }
        if (s.edit == null || s.edit.blocks() == 0) {
            say.accept("nothing to place: no preview yet (connect Blender and set /lullaby blenderbridge origin)"); // FORMAT
            return;
        }
        BlockEdit edit = s.edit;
        say.accept("placing " + edit.blocks() + " blocks ..."); // FORMAT
        long start = System.currentTimeMillis();
        s.writing = BlockEngine.write(edit, undo -> {
            s.writing = null;
            Path file = saveUndo(s.player, undo);
            // FORMAT: place finished
            say.accept("placed " + edit.blocks() + " blocks in " + (System.currentTimeMillis() - start) / 1000.0
                    + " s (" + undo.blocks() + " changed)" + (file == null ? ", but the undo file couldn't be saved" : "; /lullaby blenderbridge undo takes it back"));
            // the preview now matches the world: drop it, the next scene shows only what differs again
            if (s.layer != null) {
                BlockEngine.hide(s.layer);
                s.layer = null;
            }
        }, say);
        if (s.writing == null) say.accept("the world " + edit.world().identifier() + " isn't loaded"); // FORMAT
    }

    private Path saveUndo(UUID player, BlockEdit undo) {
        try {
            Files.createDirectories(undoFolder());
            Path file = undoFolder().resolve(player + "-" + System.currentTimeMillis() + ".lbe");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new DeflaterOutputStream(Files.newOutputStream(file))))) {
                undo.write(out);
            }
            // keep the newest 20 per player
            List<Path> mine = undoFiles(player);
            for (int i = 0; i < mine.size() - 20; i++) Files.deleteIfExists(mine.get(i));
            return file;
        } catch (IOException e) {
            plugin.getSLF4JLogger().error("LullaBlend undo file", e);
            return null;
        }
    }

    private List<Path> undoFiles(UUID player) throws IOException {
        if (!Files.isDirectory(undoFolder())) return List.of();
        try (Stream<Path> files = Files.list(undoFolder())) {
            return files.filter(p -> p.getFileName().toString().startsWith(player + "-")).sorted().toList();
        }
    }

    void undo(Session s, java.util.function.Consumer<String> say) {
        if (s.writing != null) {
            say.accept("still writing; wait until it's done"); // FORMAT
            return;
        }
        try {
            List<Path> mine = undoFiles(s.player);
            if (mine.isEmpty()) {
                say.accept("nothing to undo"); // FORMAT
                return;
            }
            Path file = mine.getLast();
            BlockEdit undo;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new InflaterInputStream(Files.newInputStream(file))))) {
                undo = BlockEdit.read(in);
            }
            s.writing = BlockEngine.write(undo, ignored -> {
                s.writing = null;
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored2) {
                    // stays; harmless
                }
                say.accept("undone: " + undo.blocks() + " blocks are back"); // FORMAT
            }, say);
            if (s.writing == null) say.accept("the world of the undo isn't loaded"); // FORMAT
        } catch (IOException | RuntimeException e) {
            say.accept("the undo file can't be read: " + e.getMessage()); // FORMAT
        }
    }
}
