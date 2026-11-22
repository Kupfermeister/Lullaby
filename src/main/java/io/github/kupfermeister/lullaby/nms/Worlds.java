package io.github.kupfermeister.lullaby.nms;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.kupfermeister.lullaby.lua.Wire;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;

/**
 * Worlds as folders on disk: list, create, load, unload, copy, delete (the script side is worlds.lua). A world's id
 * is its dimension id ("minecraft:arena_7"), the id world objects use; its folder is vanilla's dimension folder
 * ({@code world/dimensions/minecraft/arena_7}). One Worlds per plugin run (Engine), so file jobs outlast reloads.
 *
 * <p>Creating and loading go through Paper's WorldCreator: Paper rewrites how worlds load, so building a level from
 * Minecraft's own classes would break with every update. Paper reads a flat world's settings as vanilla's own JSON
 * (the same code as server.properties' generator-settings), and keeps every world's generator in the world itself
 * (vanilla's WorldGenSettings), so loading needs no settings. Paper refuses an unknown biome only with a console line
 * and makes plains instead, so biomes and blocks are checked here first.
 *
 * <p>Folders: a copy is made in {@code plugins/lullaby/temp} and moved into place when complete, so a crash, stop or
 * reload in the middle never leaves half a world among the worlds. A deleted world is moved there whole and stays
 * until the next server start, as a last chance to get it back. Everything in temp is deleted at the next start.
 *
 * <p>Threads: everything runs on the main thread except copying and emptying temp at start, which run on this
 * class's one worker thread ("Lullaby world files"). It only touches folders no world uses: temp folders, the source of a copy
 * (unloaded) and its target (does not exist yet). While a job runs, every world id it uses is "busy" and refused by
 * every other function here; the worker hands a finished job back through a queue, which {@link #tick} reads.
 */
public final class Worlds {

    // An id's two parts: vanilla's id characters, never starting with a dot (".." would leave the folder).
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_-][a-z0-9_.-]*");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_-][a-z0-9_.-]*");
    // Paper's file with the world's UUID: a copy that keeps it is refused as a duplicate of its source.
    private static final Path PAPER_METADATA = Path.of("data", "paper", "metadata.dat");

    /** The generators create_world{} takes, in the order the docs and errors list them. */
    private static final Map<String, WorldType> GENERATORS = new LinkedHashMap<>();
    static {
        GENERATORS.put("void", WorldType.FLAT);
        GENERATORS.put("flat", WorldType.FLAT);
        GENERATORS.put("normal", WorldType.NORMAL);
        GENERATORS.put("large_biomes", WorldType.LARGE_BIOMES);
        GENERATORS.put("amplified", WorldType.AMPLIFIED);
    }

    /** A file job: the world ids it uses, and when done, its error (null: it worked). */
    private static final class Job {
        final List<String> ids;
        volatile String error;
        boolean done; // main thread only: set by tick, after the ids are free again

        Job(List<String> ids) {
            this.ids = ids;
        }
    }

    private final Path temp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Lullaby world files");
        thread.setDaemon(true); // a stop mid-copy leaves only a temp folder, deleted at the next start
        return thread;
    });
    private final Set<String> busy = new HashSet<>();
    private final Map<Integer, Job> jobs = new HashMap<>();
    private final ConcurrentLinkedQueue<Job> finished = new ConcurrentLinkedQueue<>();
    private int nextJob = 1;

    /**
     * {@code temp}: plugins/lullaby/temp. What is in it from the last run (worlds deleted then, cut-off copies) is
     * deleted now, on the worker. The list is taken here, before any script runs, so a world deleted in this run
     * stays until the next start.
     */
    public Worlds(Path temp) {
        this.temp = temp;
        List<Path> left = new ArrayList<>();
        if (Files.isDirectory(temp)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(temp)) {
                for (Path p : stream) left.add(p);
            } catch (IOException e) {
                // tried again at the next start
            }
        }
        worker.execute(() -> {
            for (Path p : left) {
                try {
                    deleteTree(p);
                } catch (IOException e) {
                    // a leftover that can't be deleted now is tried again at the next start
                }
            }
        });
    }

    /** Plugin turns off. A running copy is cut off with the server (its temp folder goes at the next start). */
    public void close() {
        worker.shutdown();
    }

    /** Once per tick: frees the ids of finished jobs. */
    public void tick() {
        Job job;
        while ((job = finished.poll()) != null) {
            job.ids.forEach(busy::remove);
            job.done = true;
        }
    }

    /** A reload: the old scripts' unread job results are forgotten (running jobs still finish and free their ids). */
    public void forgetJobs() {
        jobs.values().removeIf(job -> job.done);
    }

    /**
     * A job's result, read once: null while it runs, "" when it worked, the error text when not. The library asks
     * once per tick while a script waits.
     */
    public String job(int number) {
        Job job = jobs.get(number);
        if (job == null) throw new IllegalStateException("Lullaby bug: unknown world job " + number);
        if (!job.done) return null;
        jobs.remove(number);
        return job.error == null ? "" : job.error;
    }

    // ---- reading ----

    /** Every world: one line per world, "id TAB 1" (loaded) or "id TAB 0", sorted by id. */
    public String list() {
        Set<String> ids = new TreeSet<>();
        Path root = folder("minecraft:overworld").getParent().getParent();
        try (DirectoryStream<Path> namespaces = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path namespace : namespaces) {
                String ns = namespace.getFileName().toString();
                if (!NAMESPACE.matcher(ns).matches()) continue;
                try (DirectoryStream<Path> worlds = Files.newDirectoryStream(namespace, Files::isDirectory)) {
                    for (Path world : worlds) {
                        String path = world.getFileName().toString();
                        if (!PATH.matcher(path).matches()) continue;
                        if (Files.isDirectory(world.resolve("data")) || Files.isDirectory(world.resolve("region"))) {
                            ids.add(ns + ":" + path);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // FORMAT: worlds() could not read the folder
            throw new IllegalStateException("worlds(): can't read the world folders: " + e.getMessage());
        }
        for (ServerLevel level : server().getAllLevels()) ids.add(level.dimension().identifier().toString());
        StringBuilder out = new StringBuilder();
        for (String id : ids) out.append(id).append('\t').append(loaded(id) ? '1' : '0').append('\n');
        return out.toString();
    }

    // ---- doing ----

    /** create_world{}: makes and loads a new world; gives its id. Every field is checked before anything happens. */
    public String create(Object wire) {
        String what = "create_world{}";
        Fields a = new Fields(what, wire);
        String id = id(what, "id", a.text("id", null));
        String generator = a.choice("generator", names(GENERATORS), "normal");
        boolean isVoid = generator.equals("void"), isFlat = generator.equals("flat");
        String biome = null;
        if (a.has("biome")) {
            // FORMAT: create_world{} biome with a generator that picks its own biomes
            if (!isVoid && !isFlat) throw new IllegalArgumentException(what + ": biome only works with generator "
                    + "\"void\" or \"flat\" (\"" + generator + "\" picks its own biomes)");
            biome = biome(what, a.text("biome", null));
        }
        JsonArray layers = null;
        if (a.has("layers")) {
            // FORMAT: create_world{} layers with a generator other than flat
            if (!isFlat) throw new IllegalArgumentException(what + ": layers only works with generator \"flat\"");
            layers = layers(what, a.list("layers"));
        }
        Long seed = seed(a);
        boolean structures = a.bool("structures", !isVoid && !isFlat);
        a.done();

        refuseBusy(what, id);
        // FORMAT: create_world{} with a name that is taken
        if (loaded(id) || Files.exists(folder(id))) throw new IllegalArgumentException(what + ": there is already a "
                + "world \"" + shortId(id) + "\" (load it with load_world{}, or delete it first)");

        WorldCreator creator = WorldCreator.ofKey(NamespacedKey.fromString(id)).type(GENERATORS.get(generator))
                .generateStructures(structures);
        if (seed != null) creator.seed(seed);
        if (isVoid || isFlat) {
            JsonObject settings = new JsonObject();
            if (layers == null) layers = isVoid ? new JsonArray() : defaultFlatLayers();
            settings.add("layers", layers);
            settings.addProperty("biome", biome != null ? biome : isVoid ? "minecraft:the_void" : "minecraft:plains");
            settings.addProperty("features", false); // void: no stone platform at spawn; flat: no trees or lakes
            settings.addProperty("lakes", false);
            creator.generatorSettings(settings.toString());
        }
        World world = creator.createWorld();
        // FORMAT: Paper didn't make the world
        if (world == null) throw new IllegalStateException(what + ": Paper didn't create \"" + shortId(id) + "\"");
        if (isVoid) world.setSpawnLocation(0, 64, 0); // vanilla would pick the bottom of the void
        return id;
    }

    /** load_world{}: loads a world from its folder (already loaded: nothing happens); gives its id. */
    public String load(String text) {
        String what = "load_world{}";
        String id = id(what, "id", text);
        if (loaded(id)) return id;
        refuseBusy(what, id);
        if (!Files.isDirectory(folder(id))) throw noWorld(what, id);
        World world = WorldCreator.ofKey(NamespacedKey.fromString(id)).createWorld();
        // FORMAT: Paper didn't load the world
        if (world == null) throw new IllegalStateException(what + ": Paper didn't load \"" + shortId(id) + "\"");
        return id;
    }

    /**
     * unload_world{}: moves every player in it to {@code to} (null: the main world's spawn), then unloads it. false
     * if it wasn't loaded.
     */
    public boolean unload(String text, boolean save, Location to) {
        String what = "unload_world{}";
        String id = id(what, "world", text);
        return unload(what, id, save, to);
    }

    /**
     * delete_world{}: unloads it if loaded (not saved: it is going anyway; players go to {@code to}), then moves its
     * folder into temp at once, as "arena_7 (deleted 2026-11-22 21.30.05)". It stays there as a last chance until the
     * next server start deletes it (jft 2026-11-22): moving the folder back into world/dimensions brings it back.
     */
    public void delete(String text, Location to) {
        String what = "delete_world{}";
        String id = id(what, "id", text);
        refuseMain(what, id, "deleted");
        refuseBusy(what, id);
        Path folder = folder(id);
        if (!loaded(id) && !Files.isDirectory(folder)) throw noWorld(what, id);
        unload(what, id, false, to);
        if (!Files.exists(folder)) return; // loaded but never saved: nothing on disk
        // FORMAT: the folder name of a deleted world in plugins/lullaby/temp (no ":" in Windows folder names)
        String name = shortId(id).replace(':', '-') + " (deleted "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss"));
        Path trash = temp.resolve(name + ")");
        for (int n = 2; Files.exists(trash); n++) trash = temp.resolve(name + " " + n + ")");
        try {
            Files.createDirectories(temp);
            move(folder, trash);
        } catch (IOException e) {
            // FORMAT: delete_world{} could not move the folder away
            throw new IllegalStateException(what + ": can't delete \"" + shortId(id) + "\": " + e.getMessage());
        }
    }

    /** copy_world{}: starts copying an unloaded world into a new one; gives the job's number (see {@link #job}). */
    public int copy(String fromText, String toText) {
        String what = "copy_world{}";
        String from = id(what, "from", fromText), to = id(what, "to", toText);
        // FORMAT: copy_world{} onto itself
        if (from.equals(to)) throw new IllegalArgumentException(what + ": from and to are the same world");
        refuseBusy(what, from);
        refuseBusy(what, to);
        // FORMAT: copy_world{} from a loaded world
        if (loaded(from)) throw new IllegalArgumentException(what + ": \"" + shortId(from) + "\" is loaded: unload it "
                + "first (a loaded world may be half saved)");
        Path source = folder(from), target = folder(to);
        if (!Files.isDirectory(source)) throw noWorld(what, from);
        // FORMAT: copy_world{} onto an existing world
        if (loaded(to) || Files.exists(target)) throw new IllegalArgumentException(what + ": there is already a "
                + "world \"" + shortId(to) + "\" (delete it first)");

        Job job = new Job(List.of(from, to));
        busy.addAll(job.ids);
        int number = nextJob++;
        jobs.put(number, job);
        worker.execute(() -> {
            Path copy = temp.resolve(UUID.randomUUID() + "-copy");
            try {
                Files.createDirectories(temp);
                copyTree(source, copy);
                Files.createDirectories(target.getParent());
                move(copy, target);
            } catch (IOException | RuntimeException e) {
                // FORMAT: copy_world{} failed while copying
                job.error = what + ": copying \"" + shortId(from) + "\" failed: "
                        + (e.getMessage() != null ? e.getMessage() : e.toString());
                try {
                    deleteTree(copy);
                } catch (IOException ignored) {
                    // deleted at the next start
                }
            } finally {
                finished.add(job);
            }
        });
        return number;
    }

    // ---- parts ----

    private boolean unload(String what, String id, boolean save, Location to) {
        ServerLevel level = level(id);
        if (level == null) return false;
        refuseMain(what, id, "unloaded");
        World world = level.getWorld();
        Location target = to != null ? to : Bukkit.getWorlds().getFirst().getSpawnLocation();
        // FORMAT: move_to inside the world that is going away
        if (world.equals(target.getWorld())) throw new IllegalArgumentException(what + ": move_to is in \""
                + shortId(id) + "\" itself");
        for (Player player : world.getPlayers()) player.teleport(target);
        // FORMAT: Paper kept the world loaded
        if (!Bukkit.unloadWorld(world, save)) throw new IllegalStateException(what + ": Paper didn't unload \""
                + shortId(id) + "\" (a plugin or script cancelled its world_unload event, or players are still in it)");
        return true;
    }

    /** A world's full id from a script's text ("Arena_7" -> "minecraft:arena_7"); the folder name rules are checked. */
    private static String id(String what, String field, String text) {
        // FORMAT: a required world name is missing
        if (text == null) throw new IllegalArgumentException(what + ": " + field + " is missing (a world name like \"arena_7\")");
        String t = text.toLowerCase(Locale.ROOT);
        int colon = t.indexOf(':');
        String ns = colon < 0 ? "minecraft" : t.substring(0, colon), path = colon < 0 ? t : t.substring(colon + 1);
        if (!NAMESPACE.matcher(ns).matches() || !PATH.matcher(path).matches()) {
            throw Fields.wrong(what, field, "a world name of a-z, 0-9, _, - and . (like \"arena_7\")",
                    "\"" + text + "\"");
        }
        return ns + ":" + path;
    }

    private static ResourceKey<Level> key(String id) {
        return ResourceKey.create(Registries.DIMENSION, Identifier.parse(id));
    }

    private static ServerLevel level(String id) {
        return server().getLevel(key(id));
    }

    private static boolean loaded(String id) {
        return level(id) != null;
    }

    /** Vanilla's folder for a world id (world/dimensions/<namespace>/<path>). */
    private static Path folder(String id) {
        return server().storageSource.getDimensionPath(key(id));
    }

    /** "minecraft:arena_7" -> "arena_7" for messages; other namespaces stay. */
    private static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    private void refuseBusy(String what, String id) {
        // FORMAT: a world a copy or delete is using
        if (busy.contains(id)) throw new IllegalStateException(what + ": \"" + shortId(id) + "\" is being copied "
                + "right now (try again when copy_world{} is done)");
    }

    private static void refuseMain(String what, String id, String done) {
        // FORMAT: the main world can't go away
        if (id.equals(Nms.worldIdOf(Bukkit.getWorlds().getFirst()))) {
            throw new IllegalArgumentException(what + ": the main world (\"" + shortId(id) + "\") can't be " + done
                    + " while the server runs");
        }
    }

    private static IllegalArgumentException noWorld(String what, String id) {
        // FORMAT: no world by that name
        return new IllegalArgumentException(what + ": there is no world \"" + shortId(id) + "\" (worlds() lists them)");
    }

    /** A biome id that exists ("cherry_grove" -> "minecraft:cherry_grove"); the error names close ones. */
    private static String biome(String what, String text) {
        var biomes = server().registryAccess().lookupOrThrow(Registries.BIOME);
        Identifier id = identifier(text);
        if (id != null && biomes.containsKey(id)) return id.toString();
        List<String> names = new ArrayList<>();
        for (Identifier known : biomes.keySet()) names.add(shortId(known.toString()));
        List<String> close = Fields.closeNames(text, names);
        // FORMAT: unknown biome, with close names
        throw new IllegalArgumentException(what + ": unknown biome \"" + text + "\""
                + (close.isEmpty() ? "" : " (did you mean " + String.join(", ", close) + "?)"));
    }

    /** layers = { { block = "stone", height = 3 }, ... }, bottom first, as vanilla's flat settings write them. */
    private static JsonArray layers(String what, List<Object> list) {
        JsonArray out = new JsonArray();
        int total = 0, height = server().overworld().getHeight();
        for (int i = 0; i < list.size(); i++) {
            String field = "layers[" + (i + 1) + "]";
            if (!(list.get(i) instanceof Wire.Table t) || t.kind >= 0) {
                throw Fields.wrong(what, field, "a table { block = \"stone\", height = 1 }", Fields.got(list.get(i)));
            }
            Fields layer = new Fields(what + " " + field, t);
            String text = layer.text("block", null);
            // FORMAT: a flat layer without a block
            if (text == null) throw new IllegalArgumentException(what + ": " + field + ".block is missing (a block id like \"stone\")");
            Identifier block = identifier(text);
            if (block == null || !BuiltInRegistries.BLOCK.containsKey(block)) {
                List<String> names = new ArrayList<>();
                for (Identifier known : BuiltInRegistries.BLOCK.keySet()) names.add(shortId(known.toString()));
                List<String> close = Fields.closeNames(text, names);
                // FORMAT: unknown block in a flat layer, with close names
                throw new IllegalArgumentException(what + ": " + field + ": unknown block \"" + text + "\""
                        + (close.isEmpty() ? "" : " (did you mean " + String.join(", ", close) + "?)"));
            }
            int h = layer.whole("height", 1);
            if (h < 1) throw layer.wrong("height", "at least 1", h);
            layer.done();
            total += h;
            JsonObject entry = new JsonObject();
            entry.addProperty("block", block.toString());
            entry.addProperty("height", h);
            out.add(entry);
        }
        // FORMAT: flat layers higher than the world
        if (total > height) throw new IllegalArgumentException(what + ": the layers are " + total
                + " blocks high together, but a world is only " + height + " high");
        return out;
    }

    /** Vanilla's classic flat world: bedrock, two dirt, grass. */
    private static JsonArray defaultFlatLayers() {
        JsonArray out = new JsonArray();
        for (String[] layer : new String[][]{{"minecraft:bedrock", "1"}, {"minecraft:dirt", "2"},
                {"minecraft:grass_block", "1"}}) {
            JsonObject entry = new JsonObject();
            entry.addProperty("block", layer[0]);
            entry.addProperty("height", Integer.parseInt(layer[1]));
            out.add(entry);
        }
        return out;
    }

    /** seed = a whole number, or text as in the create world screen (vanilla's own rule); missing: random. */
    private static Long seed(Fields a) {
        Object raw = a.raw("seed");
        if (raw == null) return null;
        if (raw instanceof String text) {
            OptionalLong parsed = WorldOptions.parseSeed(text);
            return parsed.isPresent() ? parsed.getAsLong() : null;
        }
        return a.long64("seed", 0);
    }

    private static Identifier identifier(String text) {
        String id = text.toLowerCase(Locale.ROOT);
        return Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
    }

    private static Map<String, String> names(Map<String, ?> map) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : map.keySet()) out.put(name, name);
        return out;
    }

    private static MinecraftServer server() {
        return MinecraftServer.getServer();
    }

    // ---- folders (worker thread, except the move in delete) ----

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    /** Copies a world folder, leaving out Paper's UUID file (so Paper takes the copy as a new world). */
    private static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = from.relativize(file);
                if (!relative.equals(PAPER_METADATA)) Files.copy(file, to.resolve(relative.toString()));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
