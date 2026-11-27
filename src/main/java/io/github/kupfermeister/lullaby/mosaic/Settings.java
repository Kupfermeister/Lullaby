package io.github.kupfermeister.lullaby.mosaic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code plugins/lullaby/sound_mosaicing/settings.txt}: {@code key = value} lines, {@code #} starts a comment. Read
 * again at every {@code /lullaby mosaic} command, so a change needs no reload. Written with the defaults (and what
 * each does) when missing. Unknown keys and bad values are errors naming the line.
 */
final class Settings {

    // FORMAT: the default settings.txt (what server owners read)
    static final String DEFAULT = """
            # Sound mosaicing settings (/lullaby mosaic play <file> [filters]). Read at every command, no reload needed.
            # A changed setting makes the next play work the file out again (results are kept per settings).

            # Most sounds started in one tick (50 ms).
            max_per_tick = 40
            # Most sounds playing at the same time. The game has about 247 sound channels; past that it drops sounds.
            max_voices = 230
            # Most streamed sounds (music, music discs) at the same time. The game has only a few stream channels.
            max_streams = 4
            # The same file plays at most rate_limit_count times in any rate_limit_ticks long window (a real sliding
            # window, not an average).
            rate_limit_count = 8
            rate_limit_ticks = 60

            # false: sounds start and stop on whole ticks (50 ms). true: on 10 ms steps, sent by a timing thread outside
            # the tick (works best on the same computer or LAN; over the internet network jitter blurs it).
            subtick = false

            # The shortest a sound may play before it is cut off (seconds). Any sound can be cut off (stopsound) the moment
            # it stops fitting; a cut-off sound frees its channel at once, so more sounds can play.
            min_length = 0.05
            # Most seconds of a sound file that are looked at. Longer files (music, long ambience) can only start at
            # their beginning and are always cut off by then, so matching them is cheap but rare.
            max_sound_seconds = 10
            # false: never use streamed sounds (music, discs); also skips downloading them (about 290 MB).
            music = true

            # Melody first: a sound's tonality (0 noise .. 1 pure tone, worked out per file and listed in tonality.txt)
            # is matched with how tonal the input is at that moment. melody_weight: bonus for tonal sounds on tonal
            # parts, noise_weight: bonus for noisy sounds on noisy parts (consonants, drums). Both can play at once.
            melody_weight = 2
            noise_weight = 0.5
            # Override a file's tonality: tonality.mob/cow/say1 = 0.9

            # Which frequencies count most: speech (300-3400 Hz double, up to 8 kHz 1.5x), music (all the same) or
            # custom (custom_weighting: Hz:weight points, straight lines between them on a log scale).
            weighting = speech
            custom_weighting = 100:0.5, 300:2, 3400:2, 8000:1

            # How loud the input is rebuilt: its loud parts equal about this many sounds at full volume.
            loudness = 3
            # How well a sound must fit what is still missing to be placed (0..1, similarity of the spectra). Higher: stricter.
            min_similarity = 0.4
            # Strictness: sound a placed sound adds where the input has none counts this many times worse than sound it
            # leaves out (1 = the same). Keeps sounds from covering the input with noise.
            overshoot_penalty = 3
            # A sound must explain at least this share of an average frame's energy to be placed.
            min_gain = 0.01
            # Rounds: after each round the best candidates are searched again on what is still missing, so later rounds
            # fill the gaps with more sounds. Stops early when a round places almost nothing.
            rounds = 4
            # Pitches tried: this many semitones down and up (the game plays 0.5x to 2x, 12 semitones each way), in steps
            # of pitch_step semitones (0.5 or 1; 0.5 is finer and about twice as slow).
            pitch_range = 12
            pitch_step = 0.5
            # Candidates kept per start time (more: better fits found, slower).
            candidates = 32
            # Worker threads for working out (0: all cores but one).
            threads = 0

            # Filters: /lullaby mosaic play <file> <filter> ... uses only the sounds of those filters. Patterns are sound
            # ids with * (any text); !pattern takes sounds out. Only the matching sounds of a file are used to play it.
            # Example filter (made for jft's test; change or add your own):
            filter.noteblox = block.note_block.*, block.anvil.land, entity.experience_orb.pickup
            """;

    int maxPerTick, maxVoices, maxStreams, rateCount, rateTicks;
    boolean subtick, music;
    double minLength, overshoot, pitchStep, maxSoundSeconds, melodyWeight, noiseWeight, loudness, minSimilarity, minGain;
    String weighting;
    double[][] customWeighting;
    int pitchRange, candidates, threads, rounds;
    final Map<String, List<String>> filters = new LinkedHashMap<>();
    final Map<String, Double> tonality = new LinkedHashMap<>();
    /** The settings as read (comments and spaces dropped), for the result cache's key. */
    String fingerprint;

    static Settings read(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, DEFAULT, StandardCharsets.UTF_8);
        }
        Map<String, String> values = new LinkedHashMap<>();
        Map<String, Integer> lineOf = new LinkedHashMap<>();
        parse(DEFAULT, values, null);
        Map<String, String> own = new LinkedHashMap<>();
        parse(Files.readString(file, StandardCharsets.UTF_8), own, lineOf);
        // the defaults' example filter is only a default while the file names no filter at all
        if (own.keySet().stream().anyMatch(k -> k.startsWith("filter."))) values.keySet().removeIf(k -> k.startsWith("filter."));
        values.putAll(own);
        Settings s = new Settings();
        StringBuilder print = new StringBuilder();
        for (var e : values.entrySet()) print.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        s.fingerprint = print.toString();
        Reader r = new Reader(values, lineOf);
        s.maxPerTick = r.whole("max_per_tick", 1, 1000);
        s.maxVoices = r.whole("max_voices", 1, 247);
        s.maxStreams = r.whole("max_streams", 0, 8);
        s.rateCount = r.whole("rate_limit_count", 1, 100000);
        s.rateTicks = r.whole("rate_limit_ticks", 1, 100000);
        s.subtick = r.bool("subtick");
        s.music = r.bool("music");
        s.minLength = r.number("min_length", 0.01, 10);
        s.overshoot = r.number("overshoot_penalty", 1, 100);
        s.rounds = r.whole("rounds", 1, 50);
        s.pitchStep = r.number("pitch_step", 0.5, 1);
        // FORMAT: settings.txt pitch_step not 0.5 or 1
        if (s.pitchStep != 0.5 && s.pitchStep != 1) throw r.bad("pitch_step", "0.5 or 1");
        s.maxSoundSeconds = r.number("max_sound_seconds", 0.5, 120);
        s.melodyWeight = r.number("melody_weight", 0, 100);
        s.noiseWeight = r.number("noise_weight", 0, 100);
        s.weighting = r.text("weighting").toLowerCase(Locale.ROOT);
        // FORMAT: settings.txt weighting unknown
        if (!List.of("speech", "music", "custom").contains(s.weighting)) throw r.bad("weighting", "speech, music or custom");
        s.customWeighting = r.points("custom_weighting");
        s.loudness = r.number("loudness", 0.01, 1000);
        s.minSimilarity = r.number("min_similarity", 0, 1);
        s.minGain = r.number("min_gain", 0, 1000);
        s.pitchRange = r.whole("pitch_range", 0, 12);
        s.candidates = r.whole("candidates", 1, 1000);
        s.threads = r.whole("threads", 0, 1024);
        for (var e : values.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("filter.")) {
                List<String> patterns = new ArrayList<>();
                for (String p : e.getValue().split(",")) if (!p.isBlank()) patterns.add(p.trim().replace("minecraft:", ""));
                s.filters.put(key.substring("filter.".length()), patterns);
            } else if (key.startsWith("tonality.")) {
                s.tonality.put(key.substring("tonality.".length()), r.number(key, 0, 1));
            }
        }
        r.done();
        return s;
    }

    /** "key = value" lines; # comments. */
    private static void parse(String text, Map<String, String> into, Map<String, Integer> lineOf) {
        String[] lines = text.split("\r?\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            if (line.isBlank()) continue;
            int eq = line.indexOf('=');
            // FORMAT: settings.txt line without =
            if (eq < 0) throw new IllegalArgumentException("settings.txt line " + (i + 1) + ": needs key = value, got \"" + line.trim() + "\"");
            String key = line.substring(0, eq).trim();
            into.put(key, line.substring(eq + 1).trim());
            if (lineOf != null) lineOf.put(key, i + 1);
        }
    }

    /** Reads and checks values; every key it never read is an unknown key (an error, so a typo can't hide). */
    private static final class Reader {
        private final Map<String, String> values;
        private final Map<String, Integer> lineOf;
        private final java.util.Set<String> read = new java.util.HashSet<>();

        Reader(Map<String, String> values, Map<String, Integer> lineOf) {
            this.values = values;
            this.lineOf = lineOf;
        }

        String text(String key) {
            read.add(key);
            return values.get(key);
        }

        IllegalArgumentException bad(String key, String need) {
            Integer line = lineOf.get(key);
            // FORMAT: settings.txt bad value
            return new IllegalArgumentException("settings.txt" + (line == null ? "" : " line " + line) + ": " + key
                    + " must be " + need + ", got \"" + values.get(key) + "\"");
        }

        double number(String key, double min, double max) {
            String v = text(key);
            try {
                double d = Double.parseDouble(v);
                if (d >= min && d <= max) return d;
            } catch (NumberFormatException ignored) {
                // falls through to the error
            }
            throw bad(key, "a number from " + num(min) + " to " + num(max));
        }

        int whole(String key, int min, int max) {
            String v = text(key);
            try {
                int d = Integer.parseInt(v);
                if (d >= min && d <= max) return d;
            } catch (NumberFormatException ignored) {
                // falls through to the error
            }
            throw bad(key, "a whole number from " + min + " to " + max);
        }

        boolean bool(String key) {
            String v = text(key);
            if (v.equals("true")) return true;
            if (v.equals("false")) return false;
            throw bad(key, "true or false");
        }

        double[] numbers(String key) {
            String[] parts = text(key).split(",");
            double[] out = new double[parts.length];
            try {
                for (int i = 0; i < parts.length; i++) {
                    out[i] = Double.parseDouble(parts[i].trim());
                    if (!(out[i] > 0)) throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw bad(key, "numbers above 0 separated by commas");
            }
            return out;
        }

        double[][] points(String key) {
            String[] parts = text(key).split(",");
            double[][] out = new double[parts.length][];
            try {
                for (int i = 0; i < parts.length; i++) {
                    String[] hz = parts[i].trim().split(":");
                    out[i] = new double[] {Double.parseDouble(hz[0]), Double.parseDouble(hz[1])};
                    if (!(out[i][0] > 0) || out[i][1] < 0 || (i > 0 && out[i][0] <= out[i - 1][0])) throw new NumberFormatException();
                }
            } catch (RuntimeException e) {
                throw bad(key, "Hz:weight points with rising Hz, like 100:0.5, 300:2");
            }
            return out;
        }

        void done() {
            for (String key : values.keySet()) {
                if (read.contains(key) || key.startsWith("filter.") || key.startsWith("tonality.")) continue;
                Integer line = lineOf.get(key);
                // FORMAT: settings.txt key removed in 0.1.2
                if (List.of("lengths", "cut_penalty", "length_bonus").contains(key)) {
                    throw new IllegalArgumentException("settings.txt" + (line == null ? "" : " line " + line) + ": " + key
                            + " is gone since Lullaby 0.1.2 (sounds are now cut off freely, see min_length): delete the line");
                }
                // FORMAT: settings.txt unknown key
                throw new IllegalArgumentException("settings.txt" + (line == null ? "" : " line " + line)
                        + ": unknown setting " + key);
            }
        }

        private static String num(double d) {
            return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
        }
    }
}
