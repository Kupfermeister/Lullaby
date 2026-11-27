package io.github.kupfermeister.lullaby.mosaic;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.kupfermeister.lullaby.nms.MosaicSender;
import io.github.kupfermeister.lullaby.nms.SoundSeeds;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * {@code /lullaby mosaic}: sound mosaicing (see package-info.java for the whole feature and how to remove it).
 * <pre>
 *   play &lt;file&gt; [filters...]   works the file in sound_mosaicing_sources out (or takes the kept result) and plays
 *                               it to you; from the console it only works it out (result and preview .wav)
 *   stop                        stops your mosaic
 * </pre>
 * Permissions {@code lullaby.command} and {@code lullaby.mosaic} (ops). Working out runs on one job thread (one job at
 * a time) and may take minutes; the command answers at once and the player hears it when it is ready. This is for
 * now: later scripts will register mosaics and play them.
 *
 * <p>Folders in {@code plugins/lullaby}: {@code sound_mosaicing_sources/} (the inputs: .wav, .mp3, .ogg) and
 * {@code sound_mosaicing/} with settings.txt, tonality.txt, assets/ (downloaded sound files), cache/ (analysed
 * sounds), results/ (one text file per worked-out mosaic: every play and stop, readable) and previews/ (.wav).
 */
public final class Mosaic {

    private static final int RESULT_FORMAT = 1;

    private static Path sources;
    private static Path home;
    private static Plugin plugin;
    private static volatile boolean busy;
    private static Dictionary dictionary;
    private static final Map<UUID, Playback> playing = new ConcurrentHashMap<>();

    private Mosaic() {
    }

    /** The {@code mosaic} branch; LullabyCommand hangs it under {@code /lullaby}. Also makes the folders. */
    public static LiteralArgumentBuilder<CommandSourceStack> command(Plugin owner) {
        plugin = owner;
        Path data = owner.getDataFolder().toPath();
        sources = data.resolve("sound_mosaicing_sources");
        home = data.resolve("sound_mosaicing");
        try {
            Files.createDirectories(sources);
            Files.createDirectories(home);
        } catch (IOException e) {
            owner.getSLF4JLogger().warn("sound mosaicing: can't make its folders ({})", e.getMessage()); // FORMAT: folder error
        }
        return Commands.literal("mosaic")
                .requires(source -> source.getSender().hasPermission("lullaby.mosaic"))
                .then(Commands.literal("play")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests((ctx, b) -> {
                                    for (String f : inputs()) b.suggest(quoted(f));
                                    return b.buildFuture();
                                })
                                .executes(ctx -> play(ctx, ""))
                                .then(Commands.argument("filters", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> {
                                            try {
                                                String typed = b.getRemaining();
                                                int space = typed.lastIndexOf(' ');
                                                String before = typed.substring(0, space + 1);
                                                for (String f : Settings.read(home.resolve("settings.txt")).filters.keySet()) {
                                                    b.suggest(before + f);
                                                }
                                            } catch (Exception ignored) {
                                                // a broken settings.txt gives no suggestions; play says why
                                            }
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> play(ctx, StringArgumentType.getString(ctx, "filters"))))))
                .then(Commands.literal("stop").executes(ctx -> {
                    CommandSender sender = ctx.getSource().getSender();
                    Playback p = sender instanceof Player pl ? playing.get(pl.getUniqueId()) : null;
                    // FORMAT: mosaic stop
                    if (p == null) {
                        tell(sender, "nothing is playing");
                    } else {
                        p.stop = true;
                        tell(sender, "stopped");
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static String quoted(String f) {
        return f.matches("[0-9A-Za-z_\\-.+]+") ? f : "\"" + f.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<String> inputs() {
        try (Stream<Path> s = Files.list(sources)) {
            return s.filter(Files::isRegularFile).map(p -> p.getFileName().toString())
                    .filter(n -> AudioFiles.INPUT_TYPES.contains(AudioFiles.type(Path.of(n)))).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    // FORMAT: mosaic chat line
    private static void tell(CommandSender to, String text) {
        to.sendMessage(Component.text("Mosaic: ", NamedTextColor.GOLD).append(Component.text(text, NamedTextColor.GRAY)));
    }

    private static int play(CommandContext<CommandSourceStack> ctx, String filterText) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "file");
        Path input = sources.resolve(name).normalize();
        if (!input.startsWith(sources) || !Files.isRegularFile(input)) {
            // FORMAT: mosaic unknown input
            tell(sender, "no file " + name + " in plugins/lullaby/sound_mosaicing_sources (there: "
                    + String.join(", ", inputs()) + ")");
            return 0;
        }
        Settings settings;
        try {
            settings = Settings.read(home.resolve("settings.txt"));
        } catch (Exception e) {
            tell(sender, e.getMessage());
            return 0;
        }
        List<String> filters = new ArrayList<>();
        for (String f : filterText.trim().split("\\s+")) {
            if (f.isEmpty()) continue;
            if (!settings.filters.containsKey(f)) {
                // FORMAT: mosaic unknown filter
                tell(sender, "no filter " + f + " in settings.txt (there: " + String.join(", ", settings.filters.keySet()) + ")");
                return 0;
            }
            filters.add(f);
        }
        if (busy) {
            tell(sender, "another mosaic is being worked out; try again when it's done"); // FORMAT: mosaic busy
            return 0;
        }
        busy = true;
        UUID listener = sender instanceof Player p ? p.getUniqueId() : null;
        Consumer<String> say = text -> Bukkit.getScheduler().runTask(plugin, () -> {
            CommandSender to = listener != null ? Bukkit.getPlayer(listener) : Bukkit.getConsoleSender();
            if (to != null) tell(to, text);
            if (listener != null) plugin.getSLF4JLogger().info("Mosaic: {}", text); // FORMAT: mosaic console line
        });
        Thread job = new Thread(() -> {
            try {
                Path result = work(input, settings, filters, say);
                if (listener != null) Bukkit.getScheduler().runTask(plugin, () -> start(listener, result, settings, say));
            } catch (Throwable e) {
                // FORMAT: mosaic failed
                say.accept("failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                plugin.getSLF4JLogger().warn("Mosaic failed", e);
            } finally {
                busy = false;
            }
        }, "Lullaby mosaic job");
        job.setDaemon(true);
        job.start();
        return Command.SINGLE_SUCCESS;
    }

    /** The job: everything up to the result file (kept: the same input, settings and filters reuse it). */
    private static Path work(Path input, Settings settings, List<String> filters, Consumer<String> say) throws Exception {
        long begun = System.currentTimeMillis();
        MessageDigest sha = MessageDigest.getInstance("SHA-1");
        sha.update(Files.readAllBytes(input));
        sha.update((RESULT_FORMAT + "\n" + settings.fingerprint + "\n" + String.join(",", filters)).getBytes(StandardCharsets.UTF_8));
        String key = HexFormat.of().formatHex(sha.digest()).substring(0, 10);
        String base = input.getFileName().toString().replaceAll("\\.[^.]*$", "") + (filters.isEmpty() ? "" : "-" + String.join("-", filters));
        Path result = home.resolve("results").resolve(base + "-" + key + ".txt");
        if (Files.exists(result)) {
            say.accept("using the kept result " + result.getFileName()); // FORMAT: mosaic cached
            return result;
        }

        // which sound files, played through which sound ids
        Map<String, SoundAssets.Asset> list = SoundAssets.list();
        Map<String, List<SoundSeeds.Use>> all = SoundSeeds.allUses();
        List<Pattern[]> rules = new ArrayList<>();
        for (String f : filters) rules.add(patterns(settings.filters.get(f)));
        List<String> files = new ArrayList<>();
        Map<String, List<SoundSeeds.Use>> usable = new java.util.HashMap<>();
        for (var e : all.entrySet()) {
            if (!list.containsKey(e.getKey())) continue;
            List<SoundSeeds.Use> uses = new ArrayList<>();
            for (SoundSeeds.Use u : e.getValue()) {
                if (u.stream() && !settings.music) continue;
                if (rules.isEmpty() || rules.stream().anyMatch(r -> allowed(r, u.sound()))) uses.add(u);
            }
            if (uses.isEmpty()) continue;
            files.add(e.getKey());
            usable.put(e.getKey(), uses);
        }
        files.sort(null);
        // FORMAT: mosaic no sounds
        if (files.isEmpty()) throw new IOException("the filters leave no sounds");
        say.accept("using " + files.size() + " sound files" + (filters.isEmpty() ? "" : " (filters " + String.join(", ", filters) + ")"));

        SoundAssets assets = new SoundAssets(home.resolve("assets"));
        List<SoundAssets.Asset> wanted = files.stream().map(list::get).toList();
        assets.ensure(wanted, say);
        int threads = settings.threads > 0 ? settings.threads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            if (dictionary == null) {
                Dictionary d = new Dictionary(home.resolve("cache").resolve("dictionary.bin"));
                d.load();
                dictionary = d;
            }
            dictionary.ensure(wanted, assets, settings.maxSoundSeconds, pool, say);
            dictionary.writeTonality(home.resolve("tonality.txt"), settings.tonality);

            List<Solver.Sound> sounds = new ArrayList<>();
            for (String f : files) {
                Dictionary.Entry e = dictionary.get(f);
                if (e == null || e.frames() == 0) continue;
                List<SoundSeeds.Use> uses = usable.get(f);
                boolean stream = uses.stream().anyMatch(SoundSeeds.Use::stream);
                sounds.add(new Solver.Sound(e, Dictionary.tonality(e, settings.tonality), uses, stream));
            }

            say.accept("reading " + input.getFileName()); // FORMAT: mosaic progress
            AudioFiles.Pcm pcm = AudioFiles.read(input, 0);
            float[] at24 = AudioFiles.resample(pcm, Spectra.RATE);
            int[] frames = new int[1];
            float[] spec = Spectra.spectrogram(at24, frames);

            Solver.Result r = new Solver(settings, sounds, pool, say).solve(spec, frames[0]);

            writeResult(result, r, sounds, settings, input);
            Path preview = home.resolve("previews").resolve(base + "-" + key + ".wav");
            Preview.write(preview, r, sounds, assets, list);
            // FORMAT: mosaic done
            say.accept("done in " + (System.currentTimeMillis() - begun) / 1000 + " s: " + r.report().trim().replace("\n", "; ")
                    + ". Preview: plugins/lullaby/sound_mosaicing/previews/" + preview.getFileName());
            return result;
        } finally {
            pool.shutdownNow();
        }
    }

    /** A filter's patterns: sound ids with * ; "!" in front takes sounds out. [0]: include (null: all), [1]: exclude. */
    private static Pattern[] patterns(List<String> list) {
        StringBuilder in = new StringBuilder();
        StringBuilder out = new StringBuilder();
        for (String p : list) {
            boolean not = p.startsWith("!");
            String glob = not ? p.substring(1) : p;
            String regex = Pattern.quote(glob).replace("*", "\\E.*\\Q");
            StringBuilder to = not ? out : in;
            if (!to.isEmpty()) to.append('|');
            to.append(regex);
        }
        return new Pattern[] {in.isEmpty() ? null : Pattern.compile(in.toString()),
                out.isEmpty() ? null : Pattern.compile(out.toString())};
    }

    private static boolean allowed(Pattern[] rule, String sound) {
        if (rule[1] != null && rule[1].matcher(sound).matches()) return false;
        return rule[0] == null || rule[0].matcher(sound).matches();
    }

    /**
     * The result file: one line per step, in time order, stops before plays of the same moment:
     * {@code <ms> play <sound id> <source> <volume> <pitch> <seed> <file>} or {@code <ms> stop <sound id> <source>}.
     * Volume and pitch are the packet's (the file's own sounds.json volume and pitch divided out).
     */
    private static void writeResult(Path file, Solver.Result r, List<Solver.Sound> sounds, Settings settings, Path input)
            throws IOException {
        record Line(int ms, boolean stop, String text) {
        }
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < r.plays().size(); i++) {
            Solver.Play p = r.plays().get(i);
            SoundSeeds.Use u = r.use().get(i);
            String src = r.source().get(i);
            int ms = p.start() * 10;
            float volume = p.gain() / u.volume();
            float pitch = (float) (p.pitch() / u.pitch());
            lines.add(new Line(ms, false, ms + " play " + u.sound() + " " + src + " "
                    + String.format(Locale.ROOT, "%.4f %.4f", volume, pitch) + " " + u.seed() + " "
                    + sounds.get(p.sound()).entry().file()));
            if (p.stopped()) {
                int stop = (p.start() + p.length()) * 10;
                lines.add(new Line(stop, true, stop + " stop " + u.sound() + " " + src));
            }
        }
        lines.sort((a, b) -> a.ms != b.ms ? Integer.compare(a.ms, b.ms) : Boolean.compare(!a.stop, !b.stop));
        StringBuilder s = new StringBuilder();
        // FORMAT: mosaic result header
        s.append("# Sound mosaic of ").append(input.getFileName()).append(", made by /lullaby mosaic play\n")
                .append("# <ms> play <sound> <source> <volume> <pitch> <seed> <file>  |  <ms> stop <sound> <source>\n");
        for (String line : r.report().split("\n")) s.append("# ").append(line).append('\n');
        for (Line l : lines) s.append(l.text).append('\n');
        Files.createDirectories(file.getParent());
        Files.writeString(file, s.toString());
    }

    private static List<MosaicSender.Step> readResult(Path file) throws IOException {
        List<MosaicSender.Step> steps = new ArrayList<>();
        for (String line : Files.readAllLines(file)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split(" ");
            int ms = Integer.parseInt(p[0]);
            if (p[1].equals("stop")) {
                steps.add(new MosaicSender.Step(ms, true, p[2], p[3], 0, 0, 0));
            } else {
                steps.add(new MosaicSender.Step(ms, false, p[2], p[3], Float.parseFloat(p[4]), Float.parseFloat(p[5]),
                        Long.parseLong(p[6])));
            }
        }
        return steps;
    }

    // ---- playing ----

    /** A mosaic playing to one player; its thread sends each step at its time. */
    private static final class Playback {
        volatile boolean stop;
    }

    /** Main thread: builds the packets, then a playback thread sends them on time. */
    private static void start(UUID listener, Path result, Settings settings, Consumer<String> say) {
        List<MosaicSender.Step> steps;
        try {
            steps = readResult(result);
        } catch (Exception e) {
            say.accept("can't read " + result.getFileName() + ": " + e.getMessage()); // FORMAT: mosaic result unreadable
            return;
        }
        MosaicSender.Prepared prepared = MosaicSender.prepare(listener, steps);
        if (prepared == null) return;
        Playback old = playing.get(listener);
        if (old != null) old.stop = true;
        Playback mine = new Playback();
        playing.put(listener, mine);
        // FORMAT: mosaic playing
        say.accept("playing (" + (steps.isEmpty() ? 0 : steps.getLast().ms() / 1000) + " s); /lullaby mosaic stop stops it."
                + " Keep every volume slider at 100%.");
        Thread t = new Thread(() -> {
            long begin = System.nanoTime() + 300_000_000L;
            try {
                for (int i = 0; i < steps.size(); i++) {
                    long due = begin + steps.get(i).ms() * 1_000_000L;
                    while (true) {
                        if (mine.stop) return;
                        long left = due - System.nanoTime();
                        if (left <= 0) break;
                        // park until 2 ms before, then spin: parking alone can be late by a timer tick on Windows
                        if (left > 2_000_000L) LockSupport.parkNanos(left - 2_000_000L);
                        else Thread.onSpinWait();
                    }
                    if (!MosaicSender.send(prepared, i)) return;
                }
            } finally {
                if (mine.stop) MosaicSender.stopAll(prepared);
                playing.remove(listener, mine);
            }
        }, "Lullaby mosaic playback");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }
}
