package io.github.kupfermeister.lullaby.mosaic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * The second mosaic method ({@code method = slices}), the simple one: time is cut into slices of
 * {@code slice_ms} (one tick, or down to 10 ms with subtick), and each slice is solved on its own, from start to end:
 * <ol>
 *   <li>Every sound that is still playing from the slice before may go on for this slice, at the volume it started
 *       with, only if that lowers the cost here (else it is stopped at the slice's start). A sound that ends inside
 *       the slice by itself just ends.
 *   <li>Then new sounds start: the one whose first slice_ms (at its pitch) lowers the cost of this slice the most,
 *       again and again, while it fits ({@code min_similarity}, {@code min_gain}, the limits).
 * </ol>
 * The cost is the same strict one as the layers method: missing sound once, added sound overshoot_penalty times,
 * weighted per frequency. A sound only ever starts at its beginning, so the candidates for a slice are worked out
 * once, before (screening: every sound at every pitch against the input, the best {@code slice_candidates} per
 * slice kept, more with precision); the greedy choice inside a slice is then exact. No tonality, layers or cut rules
 * here (cut_only_longer_than does not apply: any sound can be stopped at a slice's end); max_play_seconds does.
 * Runs on the mosaic job thread and its pool; plain data only.
 */
final class Slicer {

    private static final int B = Spectra.BINS;

    private final Settings s;
    private final List<Solver.Sound> sounds;
    private final float[] w;
    private final float[] over;
    private final ForkJoinPool pool;
    private final Consumer<String> say;
    private final int pitches;
    private final double[] rateOf;
    private final int[] shiftOf;
    private final int slice; // frames per slice
    private final int maxPlay;
    private final int candidates;
    private final double minSimilarity;
    private final double minGain;

    private float[] r;
    private int frames;

    Slicer(Settings s, List<Solver.Sound> sounds, ForkJoinPool pool, Consumer<String> say) {
        this.s = s;
        this.sounds = sounds;
        this.w = Spectra.weights(s);
        this.pool = pool;
        this.say = say;
        int each = (int) Math.round(s.pitchRange / s.pitchStep);
        this.pitches = 2 * each + 1;
        this.rateOf = new double[pitches];
        this.shiftOf = new int[pitches];
        for (int i = 0; i < pitches; i++) {
            double semis = (i - each) * s.pitchStep;
            rateOf[i] = Math.pow(2, semis / 12);
            shiftOf[i] = (int) Math.round(semis * Spectra.BINS_PER_SEMITONE);
        }
        int f = Math.max(1, (int) Math.round(s.sliceMs / 10.0));
        if (!s.subtick) f = Math.max(Spectra.FRAMES_PER_TICK, f / Spectra.FRAMES_PER_TICK * Spectra.FRAMES_PER_TICK);
        this.slice = f;
        this.maxPlay = Math.max(slice, (int) Math.round(s.maxPlaySeconds * 100));
        this.candidates = s.sliceCandidates * s.precision;
        this.minSimilarity = Math.min(1, s.minSimilarity / Math.sqrt(s.saturation));
        float o = (float) (1 + (s.overshoot - 1) / Math.sqrt(s.saturation));
        this.over = new float[B];
        for (int b = 0; b < B; b++) over[b] = w[b] * o;
        this.minGain = s.minGain / s.saturation;
    }

    private int framesAt(int stored, int pitch) {
        return (int) Math.floor((stored - 1) / rateOf[pitch]) + 1;
    }

    /** Frames the sound can play at this pitch: its whole length, at most max_play_seconds and what is stored. */
    private int horizon(int sound, int pitch) {
        Dictionary.Entry e = sounds.get(sound).entry();
        int stored = framesAt(e.frames(), pitch);
        int real = e.realFrames() < 0 ? Integer.MAX_VALUE : framesAt(e.realFrames(), pitch);
        return Math.min(Math.min(stored, real), maxPlay);
    }

    /** Ends by itself within its horizon (not longer than what is stored and max_play_seconds)? */
    private boolean endsByItself(int sound, int pitch) {
        Dictionary.Entry e = sounds.get(sound).entry();
        return e.realFrames() >= 0 && framesAt(e.realFrames(), pitch) <= maxPlay;
    }

    private void row(Dictionary.Entry e, int pitch, int t, float[] out) {
        double src = t * rateOf[pitch];
        int i0 = (int) src;
        if (i0 >= e.frames()) {
            Arrays.fill(out, 0);
            return;
        }
        int i1 = Math.min(i0 + 1, e.frames() - 1);
        float f = (float) (src - i0);
        byte[] spec = e.spec();
        int r0 = i0 * B;
        int r1 = i1 * B;
        int shift = shiftOf[pitch];
        for (int b = 0; b < B; b++) {
            int sb = b - shift;
            if (sb < 0 || sb >= B) {
                out[b] = 0;
            } else {
                float a = Spectra.fromByte(spec[r0 + sb]);
                float c = Spectra.fromByte(spec[r1 + sb]);
                out[b] = a + (c - a) * f;
            }
        }
    }

    private float cost(float v, int b) {
        return v > 0 ? w[b] * v * v : over[b] * v * v;
    }

    /** A sound playing, started in an earlier slice. */
    private static final class Active {
        final int sound, pitch, start;
        final float gain;
        int length; // frames played so far

        Active(int sound, int pitch, int start, float gain, int length) {
            this.sound = sound;
            this.pitch = pitch;
            this.start = start;
            this.gain = gain;
            this.length = length;
        }
    }

    Solver.Result solve(float[] input, int inputFrames) throws Exception {
        frames = inputFrames;
        r = input.clone();
        Shared.scale(r, frames, sounds, w, s.loudness);
        double total = 0;
        int loud = 0;
        for (int t = 0; t < frames; t++) {
            double e = 0;
            for (int b = 0; b < B; b++) e += w[b] * (double) r[t * B + b] * r[t * B + b];
            total += e;
            if (e > 1e-12) loud++;
        }
        double threshold = Math.max(1e-12, minGain * (loud > 0 ? total / loud : 0) * slice);
        int slices = (frames + slice - 1) / slice;
        int k = candidates;
        int[] candAtom = new int[slices * k];
        float[] candScore = new float[slices * k];
        Arrays.fill(candAtom, -1);
        // FORMAT: mosaic progress (slices)
        say.accept("slices: comparing " + sounds.size() + " sounds x " + pitches + " pitches at " + slices + " slices of "
                + slice * 10 + " ms");
        screen(slices, k, candAtom, candScore);
        say.accept("slices: placing"); // FORMAT: mosaic progress (slices)

        List<Solver.Play> plays = new ArrayList<>();
        List<Active> active = new ArrayList<>();
        Map<Integer, List<Integer>> startsOf = new HashMap<>();
        float[] tmp = new float[B];
        int streams = 0;
        int[] startsPerTick = new int[frames / Spectra.FRAMES_PER_TICK + 2];
        for (int sl = 0; sl < slices; sl++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int from = sl * slice;
            int to = Math.min(frames, from + slice);
            // 1. sounds from before: go on for this slice only if that helps
            for (int i = active.size() - 1; i >= 0; i--) {
                Active a = active.get(i);
                int n = horizon(a.sound, a.pitch);
                int until = Math.min(n, a.length + (to - from));
                boolean ending = until == n && endsByItself(a.sound, a.pitch);
                double drop = 0;
                Dictionary.Entry e = sounds.get(a.sound).entry();
                for (int t = a.length; t < until; t++) {
                    row(e, a.pitch, t, tmp);
                    int at = (a.start + t) * B;
                    if (a.start + t >= frames) break;
                    for (int b = 0; b < B; b++) {
                        float v = r[at + b];
                        drop += cost(v, b) - cost(v - a.gain * tmp[b], b);
                    }
                }
                boolean goOn = until > a.length && drop > 0;
                if (goOn) {
                    for (int t = a.length; t < until && a.start + t < frames; t++) {
                        row(e, a.pitch, t, tmp);
                        int at = (a.start + t) * B;
                        for (int b = 0; b < B; b++) r[at + b] -= a.gain * tmp[b];
                    }
                    a.length = until;
                }
                if (!goOn || until == n) {
                    // stopped at this slice's start, or it ran to its horizon (ends by itself or must be stopped)
                    boolean stopped = !(goOn && ending);
                    plays.add(new Solver.Play(a.sound, rateOf[a.pitch], a.start, a.length, stopped, a.gain));
                    if (sounds.get(a.sound).stream()) streams--;
                    active.remove(i);
                }
            }
            // 2. new sounds, the best first, while they help
            int tick = from / Spectra.FRAMES_PER_TICK;
            int[] used = new int[k];
            while (true) {
                if (active.size() >= s.maxVoices || startsPerTick[tick] >= s.maxPerTick) break;
                double best = threshold;
                int bestC = -1;
                float bestG = 0;
                for (int c = 0; c < k; c++) {
                    int atom = candAtom[sl * k + c];
                    if (atom < 0 || used[c] > 0) continue;
                    int sound = atom / pitches;
                    int pitch = atom % pitches;
                    int len = Math.min(to - from, horizon(sound, pitch));
                    Dictionary.Entry e = sounds.get(sound).entry();
                    double cc = 0, aa = 0, rr = 0;
                    for (int t = 0; t < len; t++) {
                        row(e, pitch, t, tmp);
                        int at = (from + t) * B;
                        for (int b = 0; b < B; b++) {
                            float a = tmp[b];
                            float v = r[at + b];
                            cc += w[b] * v * a;
                            aa += w[b] * a * a;
                            if (v > 0) rr += w[b] * v * v;
                        }
                    }
                    if (cc <= 0 || aa <= 0 || rr <= 0 || cc / Math.sqrt(aa * rr) < minSimilarity) continue;
                    float g = (float) Math.min(1, cc / aa);
                    double drop = 0;
                    for (int t = 0; t < len; t++) {
                        row(e, pitch, t, tmp);
                        int at = (from + t) * B;
                        for (int b = 0; b < B; b++) {
                            float v = r[at + b];
                            drop += cost(v, b) - cost(v - g * tmp[b], b);
                        }
                    }
                    if (drop > best) {
                        best = drop;
                        bestC = c;
                        bestG = g;
                    }
                }
                if (bestC < 0) break;
                used[bestC] = 1;
                int atom = candAtom[sl * k + bestC];
                int sound = atom / pitches;
                int pitch = atom % pitches;
                if (sounds.get(sound).stream() && streams >= s.maxStreams) continue;
                if (!rateOk(startsOf, sound, from)) continue;
                int len = Math.min(to - from, horizon(sound, pitch));
                Dictionary.Entry e = sounds.get(sound).entry();
                for (int t = 0; t < len; t++) {
                    row(e, pitch, t, tmp);
                    int at = (from + t) * B;
                    for (int b = 0; b < B; b++) r[at + b] -= bestG * tmp[b];
                }
                startsOf.computeIfAbsent(sound, x -> new ArrayList<>()).add(from);
                if (sounds.get(sound).stream()) streams++;
                startsPerTick[tick]++;
                int n = horizon(sound, pitch);
                if (len >= n) {
                    // over within its first slice: whole if it ends by itself, else stopped at its horizon
                    plays.add(new Solver.Play(sound, rateOf[pitch], from, n, !endsByItself(sound, pitch), bestG));
                    if (sounds.get(sound).stream()) streams--;
                } else {
                    active.add(new Active(sound, pitch, from, bestG, len));
                }
            }
            // FORMAT: mosaic progress (slices)
            if (slices >= 10 && (sl + 1) % (slices / 10) == 0) say.accept("slices: " + (sl + 1) * 100 / slices + "%");
        }
        for (Active a : active) plays.add(new Solver.Play(a.sound, rateOf[a.pitch], a.start, a.length, true, a.gain));

        Shared.Keys keys = Shared.keys(plays, sounds);
        double left = 0;
        for (int t = 0; t < frames; t++) for (int b = 0; b < B; b++) left += cost(r[t * B + b], b);
        long explained = Math.round(Math.max(0, 100 * (1 - left / Math.max(1e-12, total))));
        int[] voices = new int[frames + 1];
        for (Solver.Play p : keys.plays()) for (int t = p.start(); t < Math.min(frames, p.start() + p.length()); t++) voices[t]++;
        int most = 0;
        for (int v : voices) most = Math.max(most, v);
        long cut = keys.plays().stream().filter(Solver.Play::stopped).count();
        // FORMAT: mosaic result summary (slices)
        String report = "slices of " + slice * 10 + " ms: " + keys.plays().size() + " sounds (" + cut + " cut off), at most "
                + most + " at once, explains " + explained + "% of the input"
                + (keys.dropped() > 0 ? "; " + keys.dropped() + " sounds left out (no free stop key)" : "") + "\n";
        return new Solver.Result(keys.plays(), keys.use(), keys.source(), report);
    }

    /** The rate limit: at most rate_limit_count starts of one file in any rate_limit_ticks long window. */
    private boolean rateOk(Map<Integer, List<Integer>> startsOf, int sound, int start) {
        List<Integer> mine = startsOf.get(sound);
        if (mine == null) return true;
        int window = s.rateTicks * Spectra.FRAMES_PER_TICK;
        int count = 1;
        for (int i = mine.size() - 1; i >= 0 && start - mine.get(i) < window; i--) count++;
        return count <= s.rateCount;
    }

    /** The best few sound starts per slice, by how well their first slice fits the input (all cores). */
    private void screen(int slices, int k, int[] candAtom, float[] candScore) throws Exception {
        float[] rw = new float[r.length];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                rw[t * B + b] = v * (v > 0 ? w[b] : over[b]);
            }
        }
        int chunks = Math.max(1, Math.min(slices, pool.getParallelism() * 4));
        pool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk -> {
            int from = (int) ((long) slices * chunk / chunks);
            int to = (int) ((long) slices * (chunk + 1) / chunks);
            float[] head = new float[slice * B];
            float[] tmp = new float[B];
            for (int sound = 0; sound < sounds.size(); sound++) {
                Dictionary.Entry entry = sounds.get(sound).entry();
                for (int p = 0; p < pitches; p++) {
                    if (s.skipLong && !endsByItself(sound, p)) continue;
                    int len = Math.min(slice, horizon(sound, p));
                    double aw = 0;
                    for (int t = 0; t < len; t++) {
                        row(entry, p, t, tmp);
                        System.arraycopy(tmp, 0, head, t * B, B);
                        for (int b = 0; b < B; b++) aw += w[b] * tmp[b] * tmp[b];
                    }
                    if (aw <= 0) continue;
                    int atom = sound * pitches + p;
                    for (int sl = from; sl < to; sl++) {
                        int start = sl * slice;
                        int n = Math.min(len, frames - start);
                        float c0 = 0, c1 = 0, c2 = 0, c3 = 0;
                        for (int t = 0; t < n; t++) {
                            int at = (start + t) * B;
                            int ht = t * B;
                            for (int b = 0; b < B; b += 4) {
                                c0 += rw[at + b] * head[ht + b];
                                c1 += rw[at + b + 1] * head[ht + b + 1];
                                c2 += rw[at + b + 2] * head[ht + b + 2];
                                c3 += rw[at + b + 3] * head[ht + b + 3];
                            }
                        }
                        double c = (double) c0 + c1 + c2 + c3;
                        if (c <= 0) continue;
                        double g = Math.min(1, c / aw);
                        double score = 2 * g * c - g * g * aw;
                        int base = sl * k;
                        int weakest = base;
                        for (int i = base; i < base + k; i++) {
                            if (candAtom[i] < 0) {
                                weakest = i;
                                break;
                            }
                            if (candScore[i] < candScore[weakest]) weakest = i;
                        }
                        if (candAtom[weakest] < 0 || score > candScore[weakest]) {
                            candAtom[weakest] = atom;
                            candScore[weakest] = (float) score;
                        }
                    }
                }
            }
        })).get();
    }
}
