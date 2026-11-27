package io.github.kupfermeister.lullaby.mosaic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What is worked out once per sound file from its spectrogram, for both methods; nothing is picked by hand:
 * <ul>
 *   <li>tonality up to each frame: the share of the energy, up to there, in peaks (Spectra.tonalityPerFrame) while
 *       the pitch stays on the first note (an octave apart counts, the pitch guess can slip by one). A melody or a
 *       slide counts only while its first note lasts;
 *   <li>the first note (its bin; {@link #noteName}) and how long it lasts;
 *   <li>bass purity: the share of the energy below bass_below_hz;
 *   <li>stoppable: only a sound that hits again (a second attack: 6 dB up from the quietest of the 50 ms before,
 *       to within 20 dB of the loudest, at least 80 ms after the last) or that holds its loudness (within 10 dB of
 *       its loudest) for hold_seconds or more may be cut off. A single hit that fades (a step, a ding) always plays
 *       whole, a grinding or a long tone can be stopped.
 * </ul>
 * Any thread (plain data).
 */
final class Features {

    static final int NONE = -1;
    private static final int B = Spectra.BINS;
    /** Harmonics 1..6 in bins above the fundamental (2 bins per semitone) and their weights, for the pitch guess. */
    static final int[] HARMONIC = {0, 24, 38, 48, 56, 62};
    static final float[] HARMONIC_WEIGHT = {1f, 0.85f, 0.72f, 0.61f, 0.52f, 0.44f};

    double[] cumTone;
    double[] cumEnergy;
    int note = NONE;
    int noteFrames;
    float purity;
    boolean stoppable;
    int hits;
    int holdFrames;

    /** The strongest fundamental of a frame by its harmonics, NONE if the frame is quiet or not peaky. */
    static int pitchOf(float[] spec, int row, float peak, double energy, double loudest) {
        if (peak < 0.3 || energy < loudest * 1e-3 || energy <= 0) return NONE;
        int best = NONE;
        double bestScore = 0;
        for (int f0 = 0; f0 < B; f0++) {
            double score = salience(spec, row, f0);
            if (score > bestScore) {
                bestScore = score;
                best = f0;
            }
        }
        return best;
    }

    /** How strongly a frame holds the tone at bin f0 (its harmonics, weighted). */
    static double salience(float[] spec, int row, int f0) {
        double score = 0;
        for (int h = 0; h < HARMONIC.length; h++) {
            int b = f0 + HARMONIC[h];
            if (b >= B) break;
            score += spec[row + b] * HARMONIC_WEIGHT[h];
        }
        return score;
    }

    /** Same tone, an octave apart counting as the same, half a semitone of slack. */
    static boolean sameTone(int a, int b) {
        if (a == NONE || b == NONE) return false;
        int d = Math.floorMod(a - b, 24);
        return d <= 1 || d >= 23;
    }

    static Features of(Dictionary.Entry e, Settings s) {
        int n = e.frames();
        float[] spec = new float[n * B];
        for (int i = 0; i < spec.length; i++) spec[i] = Spectra.fromByte(e.spec()[i]);
        float[] peak = Spectra.tonalityPerFrame(spec, n);
        double[] energy = new double[n];
        double loudest = 0;
        double low = 0;
        double all = 0;
        for (int t = 0; t < n; t++) {
            for (int b = 0; b < B; b++) {
                double v = (double) spec[t * B + b] * spec[t * B + b];
                energy[t] += v;
                if (Spectra.frequency(b) < s.bassBelowHz) low += v;
            }
            all += energy[t];
            loudest = Math.max(loudest, energy[t]);
        }
        int[] pitch = new int[n];
        for (int t = 0; t < n; t++) pitch[t] = pitchOf(spec, t * B, peak[t], energy[t], loudest);
        Features f = new Features();
        f.purity = all > 0 ? (float) (low / all) : 0;
        int onset = 0;
        for (int t = 0; t < Math.min(n, 20); t++) if (energy[t] > energy[onset]) onset = t;
        Map<Integer, Integer> count = new HashMap<>();
        for (int t = onset; t < Math.min(n, onset + 5); t++) if (pitch[t] != NONE) count.merge(pitch[t], 1, Integer::sum);
        f.note = count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(NONE);
        f.cumTone = new double[n + 1];
        f.cumEnergy = new double[n + 1];
        int miss = 0;
        boolean ended = false;
        for (int t = 0; t < n; t++) {
            boolean holds = sameTone(pitch[t], f.note);
            f.cumTone[t + 1] = f.cumTone[t] + (holds ? energy[t] * peak[t] : 0);
            f.cumEnergy[t + 1] = f.cumEnergy[t] + energy[t];
            if (!ended && t >= onset) {
                if (holds) {
                    miss = 0;
                    f.noteFrames = t + 1;
                } else if (++miss >= 3) {
                    ended = true;
                }
            }
        }
        // hits and hold, on the energy in dB
        double peakDb = 10 * Math.log10(Math.max(1e-30, loudest));
        int last = -1000;
        for (int t = 0; t < n; t++) {
            double db = 10 * Math.log10(Math.max(1e-30, energy[t]));
            if (db >= peakDb - 10) f.holdFrames++;
            double quiet = Double.MAX_VALUE;
            for (int d = 1; d <= 5; d++) quiet = Math.min(quiet, t - d >= 0 ? 10 * Math.log10(Math.max(1e-30, energy[t - d])) : -300);
            if (db >= peakDb - 20 && db >= quiet + 6 && t - last >= 8) {
                f.hits++;
                last = t;
            }
        }
        if (f.hits == 0 && loudest > 0) f.hits = 1;
        boolean longerThanStored = e.realFrames() < 0;
        f.stoppable = f.hits >= 2 || f.holdFrames >= Math.round(s.holdSeconds * 100) || longerThanStored;
        return f;
    }

    /** How tonal the sound is up to this many of its own frames (0..1); the override if one is set. */
    float tonality(int sourceFrames, Double override) {
        if (override != null) return override.floatValue();
        int src = Math.min(cumEnergy.length - 1, Math.max(0, sourceFrames));
        return cumEnergy[src] > 0 ? (float) (cumTone[src] / cumEnergy[src]) : 0;
    }

    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    /** "C4" for a bin (bin 0 is A1, two bins per semitone; between two notes it takes the nearer, "~" marks it). */
    static String noteName(int bin) {
        if (bin == NONE) return "-";
        int midi = 33 + bin / 2;
        return NOTE_NAMES[midi % 12] + (midi / 12 - 1) + (bin % 2 == 1 ? "~" : "");
    }

    /** sounds.txt: every sound in use, most tonal first, with what was worked out about it. */
    static void writeList(Path out, List<Solver.Sound> sounds, Features[] features, int maxPlayFrames) throws IOException {
        Integer[] order = new Integer[sounds.size()];
        float[] tone = new float[sounds.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
            tone[i] = features[i].tonality(Math.min(sounds.get(i).entry().frames(), maxPlayFrames), sounds.get(i).override());
        }
        Arrays.sort(order, (a, b) -> Float.compare(tone[b], tone[a]));
        StringBuilder sb = new StringBuilder();
        // FORMAT: sounds.txt header
        sb.append("# Every sound of the last mosaic, most tonal first. Worked out from each file, nothing picked by hand.\n")
                .append("# tonality: how much of it (up to max_play_seconds) is one held tone, the tone of its first note\n")
                .append("#   (a melody counts only while its first note lasts); * = set in the config (tonality = { })\n")
                .append("# note: its first note (~: between two notes); note s: how long that note lasts\n")
                .append("# bass: share of its energy below bass_below_hz; hits: attacks; hold s: time within 10 dB of its loudest\n")
                .append("# stop: may be cut off (hits again or holds hold_seconds); else it always plays whole\n")
                .append("# tonality  note  note s  bass  hits  hold s  stop  s      file\n");
        for (int i : order) {
            Features f = features[i];
            Dictionary.Entry e = sounds.get(i).entry();
            sb.append(String.format(Locale.ROOT, "%.3f%s    %-5s %5.2f   %.2f  %4d  %5.2f   %-4s  %-6s %s%n", tone[i],
                    sounds.get(i).override() != null ? "*" : " ", noteName(f.note), f.noteFrames / 100.0, f.purity, f.hits,
                    f.holdFrames / 100.0, f.stoppable ? "yes" : "no",
                    e.realFrames() < 0 ? ">" + (int) e.seconds() : String.format(Locale.ROOT, "%.2f", e.realFrames() / 100.0),
                    e.file()));
        }
        Files.writeString(out, sb.toString());
    }
}
