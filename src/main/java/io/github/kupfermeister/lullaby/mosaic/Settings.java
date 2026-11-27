package io.github.kupfermeister.lullaby.mosaic;

import io.github.kupfermeister.lullaby.lua.Wire;
import io.github.kupfermeister.lullaby.nms.Fields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code sound_mosaicing = { ... }} section of {@code plugins/lullaby/config.lua} (the defaults and what each
 * does are written there, in defaults/config.lua). core.lua hands the whole section to Java at every load
 * ({@link Mosaic#configure}); it is read and checked here, through Fields, so a wrong or unknown key is a warning
 * naming it (and the defaults are used). The many inner numbers the methods use are worked out from the few
 * settings (below), so the config stays short.
 */
final class Settings {

    // what the config sets
    boolean slices, subtick, music;
    int sliceMs, maxPerTick, maxVoices, maxStreams, rateCount, rateTicks, precision;
    double volMelody, volBass, volNoise, volDetail, loudness, strictness, similarity, saturation, pitchStep,
            maxPlaySeconds, holdSeconds, cutThreshold, melodyTonality, melodyPriority, bassBelowHz, bassPurity;
    String weighting = "speech";
    double[][] customWeighting;
    final Map<String, List<String>> filters = new LinkedHashMap<>();
    final Map<String, Double> tonality = new LinkedHashMap<>();

    // worked out from them (fixed or derived; named so the methods read plainly)
    double minLength = 0.05, minGain, minSimilarity, overshoot, melodyWeight, noiseWeight, detailFromHz = 1000,
            detailVolume, detailSimilarity, detailOvershoot;
    boolean melodyLayer, bassLayer, detailLayer;
    int pitchRange = 12, candidates = 32, sliceCandidates = 64, threads = 0, rounds = 4, melodyRounds = 2,
            bassRounds = 2, detailRounds = 2;

    static Settings defaults() {
        return read(null);
    }

    /** Reads the section (a wire table, or null for the defaults). Throws with the config's wording when wrong. */
    static Settings read(Object wire) {
        Wire.Table empty = new Wire.Table();
        Fields a = new Fields("sound_mosaicing", wire instanceof Wire.Table t ? t : empty);
        Settings s = new Settings();
        String method = a.text("method", "slices");
        // FORMAT: config sound_mosaicing.method unknown
        if (!method.equals("slices") && !method.equals("layers")) throw a.wrong("method", "\"slices\" or \"layers\"", method);
        s.slices = method.equals("slices");
        s.subtick = a.bool("subtick", false);
        s.sliceMs = whole(a, "slice_ms", 10, 10, 50);
        Fields volume = a.table("volume");
        Fields v = volume != null ? volume : new Fields("sound_mosaicing: volume", empty);
        s.volMelody = number(v, "melody", 1, 0, 10);
        s.volBass = number(v, "bass", 1, 0, 10);
        s.volNoise = number(v, "noise", 1, 0, 10);
        s.volDetail = number(v, "detail", 0.5, 0, 1);
        v.done();
        s.loudness = number(a, "loudness", 3, 0.01, 1000);
        Object weighting = a.raw("weighting");
        if (weighting == null || weighting.equals("speech") || weighting.equals("music")) {
            if (weighting != null) s.weighting = (String) weighting;
        } else if (weighting instanceof Wire.Table t && t.kind < 0 && !t.hash.isEmpty()) {
            s.weighting = "custom";
            List<double[]> points = new ArrayList<>();
            for (var e : t.hash.entrySet()) {
                if (!(e.getKey() instanceof Number hz) || !(e.getValue() instanceof Number w) || hz.doubleValue() <= 0
                        || w.doubleValue() < 0) {
                    throw a.wrong("weighting", "\"speech\", \"music\" or { [Hz] = weight, ... }", weighting);
                }
                points.add(new double[] {hz.doubleValue(), w.doubleValue()});
            }
            points.sort((x, y) -> Double.compare(x[0], y[0]));
            s.customWeighting = points.toArray(new double[0][]);
        } else {
            throw a.wrong("weighting", "\"speech\", \"music\" or { [Hz] = weight, ... }", weighting);
        }
        s.strictness = number(a, "strictness", 2, 1, 100);
        s.similarity = number(a, "similarity", 0.3, 0, 1);
        s.saturation = number(a, "saturation", 1, 0.1, 10);
        s.precision = whole(a, "precision", 1, 1, 4);
        s.pitchStep = number(a, "pitch_step", 0.5, 0.5, 1);
        // FORMAT: config sound_mosaicing.pitch_step not 0.5 or 1
        if (s.pitchStep != 0.5 && s.pitchStep != 1) throw a.wrong("pitch_step", "0.5 or 1", s.pitchStep);
        s.maxPerTick = whole(a, "max_per_tick", 40, 1, 1000);
        s.maxVoices = whole(a, "max_voices", 230, 1, 247);
        s.maxStreams = whole(a, "max_streams", 4, 0, 8);
        Fields rate = a.table("rate_limit");
        Fields rl = rate != null ? rate : new Fields("sound_mosaicing: rate_limit", empty);
        s.rateCount = whole(rl, "count", 8, 1, 100000);
        s.rateTicks = whole(rl, "ticks", 60, 1, 100000);
        rl.done();
        s.maxPlaySeconds = number(a, "max_play_seconds", 2, 0.05, 120);
        s.holdSeconds = number(a, "hold_seconds", 0.3, 0, 100);
        s.cutThreshold = number(a, "cut_threshold", 0.3, 0, 1);
        s.melodyTonality = number(a, "melody_tonality", 0.6, 0, 1);
        s.melodyPriority = number(a, "melody_priority", 0.75, 0, 0.95);
        s.bassBelowHz = number(a, "bass_below_hz", 250, 60, 2000);
        s.bassPurity = number(a, "bass_purity", 0.8, 0, 1);
        s.music = a.bool("music", true);
        Object filters = a.raw("filters");
        if (filters == null) {
            s.filters.put("noteblox", List.of("block.note_block.*", "block.anvil.land", "entity.experience_orb.pickup"));
        } else if (filters instanceof Wire.Table t && t.kind < 0) {
            for (var e : t.hash.entrySet()) {
                if (!(e.getKey() instanceof String name) || !(e.getValue() instanceof Wire.Table list) || !list.hash.isEmpty()) {
                    throw a.wrong("filters", "{ name = { \"sound.id.*\", ... }, ... }", filters);
                }
                List<String> patterns = new ArrayList<>();
                for (Object p : list.array) {
                    if (!(p instanceof String text)) throw a.wrong("filters." + name, "a list of sound id patterns", list);
                    patterns.add(text.trim().replace("minecraft:", ""));
                }
                s.filters.put(name, patterns);
            }
        } else {
            throw a.wrong("filters", "{ name = { \"sound.id.*\", ... }, ... }", filters);
        }
        Object tonality = a.raw("tonality");
        if (tonality instanceof Wire.Table t && t.kind < 0) {
            for (var e : t.hash.entrySet()) {
                if (!(e.getKey() instanceof String file) || !(e.getValue() instanceof Number n) || n.doubleValue() < 0
                        || n.doubleValue() > 1) {
                    throw a.wrong("tonality", "{ [\"sound/file\"] = 0..1, ... }", tonality);
                }
                s.tonality.put(file, n.doubleValue());
            }
        } else if (tonality != null) {
            throw a.wrong("tonality", "{ [\"sound/file\"] = 0..1, ... }", tonality);
        }
        a.done();

        // the inner numbers, from the settings above
        s.minGain = 0.01;
        s.minSimilarity = s.similarity;
        s.overshoot = s.strictness;
        s.melodyWeight = 2 * s.volMelody;
        s.noiseWeight = s.volNoise;
        s.melodyLayer = s.volMelody > 0;
        s.bassLayer = s.volBass > 0;
        s.detailLayer = s.volDetail > 0;
        s.detailVolume = s.volDetail;
        s.detailSimilarity = s.similarity / 2;
        s.detailOvershoot = 1 + (s.strictness - 1) / 2;
        return s;
    }

    private static double number(Fields a, String field, double missing, double min, double max) {
        double d = a.number(field, missing);
        // FORMAT: config sound_mosaicing number out of range
        if (!(d >= min && d <= max)) throw a.wrong(field, "a number from " + num(min) + " to " + num(max), d);
        return d;
    }

    private static int whole(Fields a, String field, int missing, int min, int max) {
        int d = a.whole(field, missing);
        // FORMAT: config sound_mosaicing whole number out of range
        if (d < min || d > max) throw a.wrong(field, "a whole number from " + min + " to " + max, d);
        return d;
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
