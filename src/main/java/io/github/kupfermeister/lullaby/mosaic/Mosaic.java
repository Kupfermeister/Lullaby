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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
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
 *   play &lt;file&gt; [filters...] [-overwrite]   plays the kept mosaic of a file in plugins/lullaby/mosaic; works it
 *                                            out first if there is none yet, or again with -overwrite. From the
 *                                            console it only works it out (result and preview .wav)
 *   test subtick                             note block hats, ever closer, to hear whether sub-tick timing works
 *   stop                                     stops your mosaic
 * </pre>
 * Permissions {@code lullaby.command} and {@code lullaby.mosaic} (ops). Working out runs on one job thread (one job at
 * a time) and may take minutes; the command answers at once and the player hears it when it is ready. This is for
 * now: later scripts will register mosaics and play them. Settings: the {@code sound_mosaicing} section of
 * config.lua ({@link #configure}, applied at every load).
 *
 * <p>Folders in {@code plugins/lullaby}: {@code mosaic/} (your audio files: .wav, .mp3, .ogg), {@code mosaic/made/}
 * (per file and filters: the result .txt with every play and stop, readable, and the preview .wav; sounds.txt with
 * what was worked out about each sound) and {@code cache/mosaic/} (downloaded sound files and their analysis; never
 * needs touching).
 */
public final class Mosaic {

    private static Path inputs;
    private static Path made;
    private static Path cache;
    private static Plugin plugin;
    private static volatile Settings settings = Settings.defaults();
    private static volatile boolean busy;
    private static Dictionary dictionary;
    private static final Map<UUID, Playback> playing = new ConcurrentHashMap<>();

    private Mosaic() {
    }

    /**
     * The config's {@code sound_mosaicing} section, from core.lua at every load (main thread). Gives back nil or a
     * warning text; a wrong section warns and the defaults are used.
     */
    public static String configure(Object wire) {
        try {
            settings = Settings.read(wire);
            return null;
        } catch (IllegalArgumentException e) {
            settings = Settings.defaults();
            return e.getMessage() + " (sound mosaicing uses its defaults)"; // FORMAT: bad sound_mosaicing config
        }
    }

    /** The {@code mosaic} branch; LullabyCommand hangs it under {@code /lullaby}. Also makes the folders. */
    public static LiteralArgumentBuilder<CommandSourceStack> command(Plugin owner) {
        plugin = owner;
        Path data = owner.getDataFolder().toPath();
        inputs = data.resolve("mosaic");
        made = inputs.resolve("made");
        cache = data.resolve("cache").resolve("mosaic");
        try {
            Files.createDirectories(inputs);
        } catch (IOException e) {
            owner.getSLF4JLogger().warn("sound mosaicing: can't make its folder ({})", e.getMessage()); // FORMAT: folder error
        }
        return Commands.literal("mosaic")
                .requires(source -> source.getSender().hasPermission("lullaby.mosaic"))
                .then(Commands.literal("play")
                        .then(Commands.argument("file", StringArgumentType.string())
                                .suggests((ctx, b) -> {
                                    for (String f : inputFiles()) b.suggest(quoted(f), hint(fileHint(f)));
                                    return b.buildFuture();
                                })
                                .executes(ctx -> play(ctx, ""))
                                .then(Commands.argument("filters", StringArgumentType.greedyString())
                                        .suggests((ctx, b) -> {
                                            String typed = b.getRemaining();
                                            String before = typed.substring(0, typed.lastIndexOf(' ') + 1);
                                            for (var f : settings.filters.entrySet()) {
                                                // FORMAT: hint of a filter in tab completion
                                                b.suggest(before + f.getKey(), hint("only these sounds: " + String.join(", ", f.getValue())));
                                            }
                                            // FORMAT: hint of -overwrite in tab completion
                                            b.suggest(before + "-overwrite", hint("work it out again (after changing the config)"));
                                            return b.buildFuture();
                                        })
                                        .executes(ctx -> play(ctx, StringArgumentType.getString(ctx, "filters"))))))
                .then(Commands.literal("test").then(Commands.literal("subtick")
                        .executes(ctx -> subtickTest(ctx.getSource().getSender()))))
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

    private static com.mojang.brigadier.Message hint(String text) {
        return new com.mojang.brigadier.LiteralMessage(text);
    }

    /** The tooltip of an input file: kept mosaics play at once, others are worked out first. */
    private static String fileHint(String file) {
        String base = file.replaceAll("\\.[^.]*$", "");
        // FORMAT: hint of an input file in tab completion
        return Files.exists(made.resolve(base + ".txt")) ? "worked out: plays at once" : "not worked out yet: takes a while first";
    }

    private static String quoted(String f) {
        return f.matches("[0-9A-Za-z_\\-.+]+") ? f : "\"" + f.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<String> inputFiles() {
        try (Stream<Path> s = Files.list(inputs)) {
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

    private static int play(CommandContext<CommandSourceStack> ctx, String rest) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "file");
        Path input = inputs.resolve(name).normalize();
        if (!input.startsWith(inputs) || !Files.isRegularFile(input)) {
            // FORMAT: mosaic unknown input
            tell(sender, "no file " + name + " in plugins/lullaby/mosaic (there: " + String.join(", ", inputFiles()) + ")");
            return 0;
        }
        Settings s = settings;
        List<String> filters = new ArrayList<>();
        boolean overwrite = false;
        for (String f : rest.trim().split("\\s+")) {
            if (f.isEmpty()) continue;
            if (f.equals("-overwrite")) {
                overwrite = true;
                continue;
            }
            if (!s.filters.containsKey(f)) {
                // FORMAT: mosaic unknown filter
                tell(sender, "no filter " + f + " in the config (sound_mosaicing.filters: "
                        + String.join(", ", s.filters.keySet()) + ")");
                return 0;
            }
            filters.add(f);
        }
        String base = input.getFileName().toString().replaceAll("\\.[^.]*$", "")
                + (filters.isEmpty() ? "" : "-" + String.join("-", filters));
        Path result = made.resolve(base + ".txt");
        UUID listener = sender instanceof Player p ? p.getUniqueId() : null;
        Consumer<String> say = text -> Bukkit.getScheduler().runTask(plugin, () -> {
            CommandSender to = listener != null ? Bukkit.getPlayer(listener) : Bukkit.getConsoleSender();
            if (to != null) tell(to, text);
            if (listener != null) plugin.getSLF4JLogger().info("Mosaic: {}", text); // FORMAT: mosaic console line
        });
        if (Files.exists(result) && !overwrite) {
            // FORMAT: mosaic plays the kept result
            tell(sender, "playing the kept mosaic " + base + " (-overwrite works it out again)");
            if (listener != null) start(listener, result, say);
            return Command.SINGLE_SUCCESS;
        }
        if (busy) {
            tell(sender, "another mosaic is being worked out; try again when it's done"); // FORMAT: mosaic busy
            return 0;
        }
        busy = true;
        Thread job = new Thread(() -> {
            try {
                work(input, base, result, s, filters, say);
                if (listener != null) Bukkit.getScheduler().runTask(plugin, () -> start(listener, result, say));
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

    /** The job: everything up to the result file and its preview. */
    private static void work(Path input, String base, Path result, Settings s, List<String> filters, Consumer<String> say)
            throws Exception {
        long begun = System.currentTimeMillis();
        // which sound files, played through which sound ids
        Map<String, SoundAssets.Asset> list = SoundAssets.list();
        Map<String, List<SoundSeeds.Use>> all = SoundSeeds.allUses();
        List<Pattern[]> rules = new ArrayList<>();
        for (String f : filters) rules.add(patterns(s.filters.get(f)));
        List<String> files = new ArrayList<>();
        Map<String, List<SoundSeeds.Use>> usable = new java.util.HashMap<>();
        for (var e : all.entrySet()) {
            if (!list.containsKey(e.getKey())) continue;
            List<SoundSeeds.Use> uses = new ArrayList<>();
            for (SoundSeeds.Use u : e.getValue()) {
                if (u.stream() && !s.music) continue;
                // strict: the sound id played and the one whose own list has the file must both pass
                if (rules.isEmpty() || rules.stream().anyMatch(r -> allowed(r, u.sound()) && allowed(r, u.owner()))) uses.add(u);
            }
            if (uses.isEmpty()) continue;
            files.add(e.getKey());
            usable.put(e.getKey(), uses);
        }
        files.sort(null);
        // FORMAT: mosaic no sounds
        if (files.isEmpty()) throw new IOException("the filters leave no sounds");
        say.accept("using " + files.size() + " sound files" + (filters.isEmpty() ? "" : " (filters " + String.join(", ", filters) + ")"));

        SoundAssets assets = new SoundAssets(cache.resolve("assets"));
        List<SoundAssets.Asset> wanted = files.stream().map(list::get).toList();
        assets.ensure(wanted, say);
        int threads = s.threads > 0 ? s.threads : Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        ForkJoinPool pool = new ForkJoinPool(threads);
        try {
            if (dictionary == null) {
                Dictionary d = new Dictionary(cache.resolve("dictionary.bin"));
                d.load();
                dictionary = d;
            }
            // stored: twice the longest play, because at pitch 2 a sound runs through its file twice as fast
            dictionary.ensure(wanted, assets, Math.max(2, s.maxPlaySeconds * 2), pool, say);

            List<Solver.Sound> sounds = new ArrayList<>();
            for (String f : files) {
                Dictionary.Entry e = dictionary.get(f);
                if (e == null || e.frames() == 0) continue;
                List<SoundSeeds.Use> uses = usable.get(f);
                boolean stream = uses.stream().anyMatch(SoundSeeds.Use::stream);
                sounds.add(new Solver.Sound(e, s.tonality.get(f), uses, stream));
            }
            Features[] features = new Features[sounds.size()];
            pool.submit(() -> IntStream.range(0, sounds.size()).parallel()
                    .forEach(i -> features[i] = Features.of(sounds.get(i).entry(), s))).get();
            Files.createDirectories(made);
            Features.writeList(made.resolve("sounds.txt"), sounds, features, (int) Math.round(s.maxPlaySeconds * 100));

            say.accept("reading " + input.getFileName()); // FORMAT: mosaic progress
            AudioFiles.Pcm pcm = AudioFiles.read(input, 0);
            float[] at24 = AudioFiles.resample(pcm, Spectra.RATE);
            int[] frames = new int[1];
            float[] spec = Spectra.spectrogram(at24, frames);

            Solver.Result r = s.slices
                    ? new Slicer(s, sounds, features, pool, say).solve(spec, frames[0])
                    : new Solver(s, sounds, features, pool, say).solve(spec, frames[0]);

            writeResult(result, r, sounds, input);
            Path preview = made.resolve(base + ".wav");
            Preview.write(preview, r, sounds, assets, list);
            // FORMAT: mosaic done
            say.accept("done in " + (System.currentTimeMillis() - begun) / 1000 + " s: " + r.report().trim().replace("\n", "; ")
                    + ". Preview: plugins/lullaby/mosaic/made/" + preview.getFileName());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * A filter's patterns: sound ids, where * stands for one part of the id (no dots: block.note_block.* is
     * block.note_block.harp but not block.note_block.imitate.zombie) and ** for any text; "!" in front takes sounds
     * out. [0]: include (null: all), [1]: exclude.
     */
    private static Pattern[] patterns(List<String> list) {
        StringBuilder in = new StringBuilder();
        StringBuilder out = new StringBuilder();
        for (String p : list) {
            boolean not = p.startsWith("!");
            String glob = not ? p.substring(1) : p;
            String regex = Pattern.quote(glob).replace("**", "\\E.*\\Q").replace("*", "\\E[^.]*\\Q");
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
    private static void writeResult(Path file, Solver.Result r, List<Solver.Sound> sounds, Path input) throws IOException {
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

    /**
     * Main thread: builds the packets, then a playback thread sends each at its time, measured against the clock;
     * at the end it says how late the sends were (so it can be checked that steps between ticks really go out
     * between ticks; what the player's game does with them is up to the game).
     */
    private static void start(UUID listener, Path result, Consumer<String> say) {
        List<MosaicSender.Step> steps;
        try {
            steps = readResult(result);
        } catch (Exception e) {
            say.accept("can't read " + result.getFileName() + ": " + e.getMessage()); // FORMAT: mosaic result unreadable
            return;
        }
        play(listener, steps, say);
    }

    /**
     * {@code /lullaby mosaic test subtick}: no audio file and no mosaic, only note block hats sent straight through the
     * playback thread, so what is heard is the timing alone. The gap between hats shrinks evenly (on a log scale) from
     * 500 ms (10 ticks) to 5 ms (a tenth of a tick) over 20 s, then stays at 5 ms for 2 s. Without sub-tick timing the
     * hats would bunch up into 50 ms steps once the gap is below a tick.
     */
    private static int subtickTest(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            tell(sender, "the test plays to you: use it in the game"); // FORMAT: mosaic test from the console
            return 0;
        }
        List<MosaicSender.Step> steps = new ArrayList<>();
        double t = 0;
        while (t < 22) {
            steps.add(new MosaicSender.Step((int) Math.round(t * 1000), false, "block.note_block.hat", "master", 1, 1, 0));
            double gap = t < 20 ? 0.5 * Math.pow(0.005 / 0.5, t / 20) : 0.005;
            t += gap;
        }
        UUID listener = p.getUniqueId();
        play(listener, steps, text -> Bukkit.getScheduler().runTask(plugin, () -> {
            Player to = Bukkit.getPlayer(listener);
            if (to != null) tell(to, text);
        }));
        return Command.SINGLE_SUCCESS;
    }

    /** Main thread: builds the packets, then a playback thread sends each at its time. */
    private static void play(UUID listener, List<MosaicSender.Step> steps, Consumer<String> say) {
        MosaicSender.Prepared prepared = MosaicSender.prepare(listener, steps);
        if (prepared == null) return;
        Playback old = playing.get(listener);
        if (old != null) old.stop = true;
        Playback mine = new Playback();
        playing.put(listener, mine);
        long offTick = steps.stream().filter(st -> st.ms() % 50 != 0).count();
        // FORMAT: mosaic playing
        say.accept("playing (" + (steps.isEmpty() ? 0 : steps.getLast().ms() / 1000) + " s, " + steps.size() + " steps, "
                + offTick + " between ticks); /lullaby mosaic stop stops it. Keep every volume slider at 100%.");
        Thread t = new Thread(() -> {
            long begin = System.nanoTime() + 300_000_000L;
            long lateSum = 0;
            long lateMax = 0;
            int sent = 0;
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
                    long late = System.nanoTime() - due;
                    if (!MosaicSender.send(prepared, i)) return;
                    lateSum += late;
                    lateMax = Math.max(lateMax, late);
                    sent++;
                }
            } finally {
                if (mine.stop) MosaicSender.stopAll(prepared);
                playing.remove(listener, mine);
                // FORMAT: mosaic timing report
                if (sent > 0) say.accept(String.format(Locale.ROOT, "sent %d steps, on average %.2f ms late, at most %.2f ms",
                        sent, lateSum / 1e6 / sent, lateMax / 1e6));
            }
        }, "Lullaby mosaic playback");
        t.setDaemon(true);
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }
}
