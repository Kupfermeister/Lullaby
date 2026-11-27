package io.github.kupfermeister.lullaby.mosaic;

import io.github.kupfermeister.lullaby.nms.SoundSeeds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * The mosaic itself: which sound files start when, at which pitch and volume, and where each is cut off.
 *
 * <p>The input and every sound are spectrograms (Spectra). R, the residual, starts as the input and is what is
 * still missing. A sound placed at frame s with gain g takes g times its spectrogram off R. The cost of R is its
 * weighted energy ({@code weighting}), where a negative part (sound the input doesn't have) counts
 * {@code overshoot_penalty} times. Each placement is the one that lowers that cost the most (greedy matching
 * pursuit), as long as it is similar enough ({@code min_similarity}) and worth it ({@code min_gain}). "Lazy": a
 * candidate's score only gets worse when R changes under it, so a popped score is worked out again only if something
 * was placed over its frames since.
 *
 * <ol>
 *   <li>Screening (all cores): for every start time and every sound at every pitch, how well its first 40 ms fit R;
 *       the best {@code candidates} per start time are kept. This is the only step that looks at every sound.
 *   <li>Each candidate's length: the sound plays exactly as long as it keeps lowering the cost, then it is cut off
 *       (stopsound), at least {@code min_length}. A cut-off sound frees its channel at once. Files longer than
 *       {@code max_sound_seconds} are always cut off by then.
 *   <li>Every score is multiplied by the melody preference: 1 + melody_weight * tonality * input tonality + noise_weight
 *       * (1 - tonality) * (1 - input tonality), so a tonal sound wins on tonal moments and a noisy one on noisy moments.
 *   <li>Limits checked before each placement: max_per_tick starts per tick, max_voices and max_streams playing at
 *       once, the rate limit per file.
 *   <li>Rounds ({@code rounds}): screening runs again on what is still missing, then placing again.
 *   <li>Stop keys: stopsound stops every playing sound of one sound id on one source (volume slider). So every play
 *       gets a sound id (any whose list has the file) and a source such that no stop hits another play. A cut-off
 *       play that gets no free key is left out.
 * </ol>
 * Runs on the mosaic job thread and its pool; plain data only.
 */
final class Solver {

    /** A sound file usable here: its analysis, tonality and the sound ids (filtered) that can play it. */
    record Sound(Dictionary.Entry entry, float tonality, List<SoundSeeds.Use> uses, boolean stream) {
    }

    /** One placed sound. pitch: real playback rate (0.5..2); length: frames it plays; stopped: cut off then. */
    record Play(int sound, double pitch, int start, int length, boolean stopped, float gain) {
    }

    /** The finished mosaic: plays with their keys, and numbers for the report. */
    record Result(List<Play> plays, List<SoundSeeds.Use> use, List<String> source, String report) {
    }

    private static final int B = Spectra.BINS;
    private static final int HEAD = 4; // frames looked at when screening

    private final Settings s;
    private final List<Sound> sounds;
    private final float[] w;
    private final ForkJoinPool pool;
    private final Consumer<String> say;
    private final int pitches;
    private final double[] rateOf; // pitch index -> playback rate
    private final int[] shiftOf; // pitch index -> bins the spectrum moves
    private final int lowest; // pitch index of the lowest pitch (0)
    private final int grid;
    private final int minLength;
    private final float overshoot;

    private float[] r; // residual, frames x B
    private int frames;
    private float[] energy; // weighted energy of the (scaled) input per frame
    private double[] cumTonalEnergy;
    private double[] cumEnergy;

    Solver(Settings s, List<Sound> sounds, ForkJoinPool pool, Consumer<String> say) {
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
        this.lowest = 0;
        this.grid = s.subtick ? 1 : Spectra.FRAMES_PER_TICK;
        int f = (int) Math.round(s.minLength * 100);
        if (!s.subtick) f = Math.max(grid, (f + grid - 1) / grid * grid);
        this.minLength = Math.max(1, f);
        this.overshoot = (float) s.overshoot;
    }

    // ---- a sound at a pitch ----

    /** Frames the stored part lasts at this pitch. */
    private int framesAt(int stored, int pitch) {
        return (int) Math.floor((stored - 1) / rateOf[pitch]) + 1;
    }

    private int pitchedFrames(int sound, int pitch) {
        return framesAt(sounds.get(sound).entry.frames(), pitch);
    }

    /** Frames it plays when not cut off, -1 if longer than what is stored (then it must be cut). */
    private int realFrames(int sound, int pitch) {
        int real = sounds.get(sound).entry.realFrames();
        return real < 0 ? -1 : framesAt(real, pitch);
    }

    /** Row t of the sound at this pitch: frames squeezed by the rate, bins shifted. */
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

    private double preference(float tonality, int from, int to) {
        to = Math.min(to, frames);
        double e = to > from ? cumEnergy[to] - cumEnergy[from] : 0;
        double kappa = e > 0 ? (cumTonalEnergy[to] - cumTonalEnergy[from]) / e : 0.5;
        return 1 + s.melodyWeight * tonality * kappa + s.noiseWeight * (1 - tonality) * (1 - kappa);
    }

    /** The cost of one residual value: missing sound counts once, added sound overshoot_penalty times. */
    private float cost(float v) {
        return v >= 0 ? v * v : overshoot * v * v;
    }

    // ---- solving ----

    Result solve(float[] input, int inputFrames) throws Exception {
        frames = inputFrames;
        prepare(input);
        int positions = (frames + grid - 1) / grid;
        int k = s.candidates;
        int[] candAtom = new int[positions * k];
        float[] candScore = new float[positions * k];

        double average = 0;
        int loud = 0;
        double total = 0;
        for (int t = 0; t < frames; t++) {
            total += energy[t];
            if (energy[t] > 1e-12) {
                average += energy[t];
                loud++;
            }
        }
        average = loud > 0 ? average / loud : 0;
        double threshold = Math.max(1e-12, s.minGain * average);

        int margin = 0;
        for (int i = 0; i < sounds.size(); i++) margin = Math.max(margin, framesAt(sounds.get(i).entry.frames(), lowest) + 2);
        int[] voices = new int[frames + margin + 1];
        int[] streams = new int[frames + margin + 1];
        int[] starts = new int[frames / Spectra.FRAMES_PER_TICK + 2];
        int[] modified = new int[frames];
        Map<Integer, List<Integer>> startsOf = new HashMap<>();
        List<Play> plays = new ArrayList<>();
        int step = 0;
        StringBuilder report = new StringBuilder();
        float[] tmp = new float[B];

        for (int round = 0; round < s.rounds; round++) {
            // FORMAT: mosaic progress
            say.accept("round " + (round + 1) + ": comparing " + sounds.size() + " sounds x " + pitches + " pitches at "
                    + positions + " start times");
            Arrays.fill(candAtom, -1);
            Arrays.fill(candScore, 0);
            screen(positions, k, candAtom, candScore);
            List<Entry> entries = pool.submit(() -> IntStream.range(0, positions).parallel().mapToObj(pos -> {
                List<Entry> out = new ArrayList<>();
                float[] buf = new float[B];
                for (int c = 0; c < k; c++) {
                    int atom = candAtom[pos * k + c];
                    if (atom < 0) continue;
                    Entry e = evaluate(atom, pos * grid, buf);
                    if (e != null && e.score >= threshold) out.add(e);
                }
                return out;
            }).flatMap(List::stream).toList()).get();
            for (Entry e : entries) e.step = step; // scored on R as it is now
            PriorityQueue<Entry> heap = new PriorityQueue<>((a, b) -> Double.compare(b.score, a.score));
            heap.addAll(entries);
            int placed = 0;
            while (!heap.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Entry e = heap.poll();
                if (e.score < threshold) break;
                int sound = e.atom / pitches;
                int pitch = e.atom % pitches;
                int span = Math.min(frames, e.start + pitchedFrames(sound, pitch));
                boolean stale = false;
                for (int t = e.start; t < span; t++) {
                    if (modified[t] > e.step) {
                        stale = true;
                        break;
                    }
                }
                if (stale) {
                    Entry again = evaluate(e.atom, e.start, tmp);
                    if (again != null && again.score >= threshold) {
                        again.step = step;
                        heap.add(again);
                    }
                    continue;
                }
                int playing = e.stopped ? e.length : realFrames(sound, pitch);
                if (!fits(sound, e.start, playing, voices, streams, starts, startsOf)) continue;
                Dictionary.Entry de = sounds.get(sound).entry;
                for (int t = 0; t < e.length && e.start + t < frames; t++) {
                    row(de, pitch, t, tmp);
                    int at = (e.start + t) * B;
                    for (int b = 0; b < B; b++) r[at + b] -= e.gain * tmp[b];
                    modified[e.start + t] = step + 1;
                }
                step++;
                for (int t = e.start; t < e.start + playing; t++) {
                    voices[t]++;
                    if (sounds.get(sound).stream) streams[t]++;
                }
                starts[e.start / Spectra.FRAMES_PER_TICK]++;
                startsOf.computeIfAbsent(sound, x -> new ArrayList<>()).add(e.start);
                plays.add(new Play(sound, rateOf[pitch], e.start, playing, e.stopped, e.gain));
                placed++;
            }
            // FORMAT: mosaic round report
            String line = "round " + (round + 1) + ": " + placed + " placed, explains " + explained(total) + "%";
            say.accept(line);
            report.append(line).append('\n');
            if (placed < Math.max(3, plays.size() / 100)) break; // nothing much left to find
        }

        Keys keys = keys(plays);
        int maxVoices = 0;
        for (int v : voices) maxVoices = Math.max(maxVoices, v);
        long cut = keys.plays.stream().filter(Play::stopped).count();
        // FORMAT: mosaic result summary
        String summary = keys.plays.size() + " sounds (" + cut + " cut off), at most " + maxVoices
                + " at once, explains " + explained(total) + "% of the input"
                + (keys.dropped > 0 ? "; " + keys.dropped + " cut-off sounds left out (no free stop key)" : "");
        report.append(summary).append('\n');
        return new Result(keys.plays, keys.use, keys.source, report.toString());
    }

    /** How much of the input's cost is gone, in percent (added sound counted with its penalty). */
    private long explained(double total) {
        double left = 0;
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) left += w[b] * cost(r[t * B + b]);
        }
        return Math.round(Math.max(0, 100 * (1 - left / Math.max(1e-12, total))));
    }

    /** Scales the input to the sounds' loudness, weighted energy and tonality per frame. */
    private void prepare(float[] input) {
        r = input.clone();
        // loudest frame of each sound at its own pitch (weighted), their median: "one sound at full volume"
        double[] peaks = new double[sounds.size()];
        for (int i = 0; i < sounds.size(); i++) {
            Dictionary.Entry e = sounds.get(i).entry;
            double best = 0;
            for (int t = 0; t < e.frames(); t++) {
                double sum = 0;
                for (int b = 0; b < B; b++) {
                    double v = e.at(t, b);
                    sum += w[b] * v * v;
                }
                best = Math.max(best, sum);
            }
            peaks[i] = Math.sqrt(best);
        }
        Arrays.sort(peaks);
        double reference = peaks.length == 0 ? 1 : peaks[peaks.length / 2];
        double[] norms = new double[frames];
        for (int t = 0; t < frames; t++) {
            double sum = 0;
            for (int b = 0; b < B; b++) {
                double v = r[t * B + b];
                sum += w[b] * v * v;
            }
            norms[t] = Math.sqrt(sum);
        }
        double[] sorted = norms.clone();
        Arrays.sort(sorted);
        double p95 = sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.95)];
        double scale = p95 > 0 ? s.loudness * reference / p95 : 1;
        for (int i = 0; i < r.length; i++) r[i] *= (float) scale;
        energy = new float[frames];
        for (int t = 0; t < frames; t++) energy[t] = (float) (norms[t] * norms[t] * scale * scale);
        float[] per = Spectra.tonalityPerFrame(r, frames);
        float[] tonal = new float[frames];
        for (int t = 0; t < frames; t++) {
            double sum = 0;
            int n = 0;
            for (int d = -2; d <= 2; d++) {
                if (t + d >= 0 && t + d < frames) {
                    sum += per[t + d];
                    n++;
                }
            }
            tonal[t] = (float) (sum / n);
        }
        cumEnergy = new double[frames + 1];
        cumTonalEnergy = new double[frames + 1];
        for (int t = 0; t < frames; t++) {
            cumEnergy[t + 1] = cumEnergy[t] + energy[t];
            cumTonalEnergy[t + 1] = cumTonalEnergy[t] + energy[t] * tonal[t];
        }
    }

    /** Step 1: the best few sounds per start time, by how well their first 40 ms fit R as it is now. */
    private void screen(int positions, int k, int[] candAtom, float[] candScore) throws Exception {
        float[] rw = new float[r.length];
        float[] missing = new float[frames + 1]; // cumulative weighted energy still missing (R > 0)
        for (int t = 0; t < frames; t++) {
            double m = 0;
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                rw[t * B + b] = v * w[b];
                if (v > 0) m += w[b] * v * v;
            }
            missing[t + 1] = (float) (missing[t] + m);
        }
        int chunks = Math.max(1, Math.min(positions, pool.getParallelism() * 4));
        pool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk -> {
            int from = (int) ((long) positions * chunk / chunks);
            int to = (int) ((long) positions * (chunk + 1) / chunks);
            float[] head = new float[HEAD * B];
            float[] tmp = new float[B];
            boolean[] quiet = new boolean[to - from];
            for (int pos = from; pos < to; pos++) {
                int st = pos * grid;
                quiet[pos - from] = missing[Math.min(frames, st + HEAD)] - missing[st] <= 1e-12;
            }
            for (int sound = 0; sound < sounds.size(); sound++) {
                Sound snd = sounds.get(sound);
                for (int p = 0; p < pitches; p++) {
                    if (pitchedFrames(sound, p) < minLength) continue; // too short to ever be placed
                    int len = Math.min(HEAD, pitchedFrames(sound, p));
                    double aw = 0;
                    for (int t = 0; t < len; t++) {
                        row(snd.entry, p, t, tmp);
                        System.arraycopy(tmp, 0, head, t * B, B);
                        for (int b = 0; b < B; b++) aw += w[b] * tmp[b] * tmp[b];
                    }
                    if (aw <= 0) continue;
                    int atom = sound * pitches + p;
                    for (int pos = from; pos < to; pos++) {
                        if (quiet[pos - from]) continue;
                        int start = pos * grid;
                        int n = Math.min(len, frames - start);
                        // four separate sums: the CPU works on them side by side (the hottest loop of all)
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
                        double score = (2 * g * c - g * g * aw) * preference(snd.tonality, start, start + len);
                        int base = pos * k;
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

    /** A candidate placement with its length. */
    private static final class Entry {
        final int atom;
        final int start;
        final int length;
        final boolean stopped;
        final float gain;
        final double score;
        int step;

        Entry(int atom, int start, int length, boolean stopped, float gain, double score) {
            this.atom = atom;
            this.start = start;
            this.length = length;
            this.stopped = stopped;
            this.gain = gain;
            this.score = score;
        }
    }

    /**
     * Step 2, strict: the gain from least squares over the part that fits, then the cost drop frame by frame with
     * that gain (added sound counted overshoot_penalty times); the sound plays up to the frame where the total drop
     * is highest, so it is cut off as soon as the rest would only add what the input doesn't have.
     */
    private Entry evaluate(int atom, int start, float[] tmp) {
        int sound = atom / pitches;
        int pitch = atom % pitches;
        Sound snd = sounds.get(sound);
        int n = pitchedFrames(sound, pitch);
        boolean mustStop = snd.entry.realFrames() < 0;
        int end = Math.min(n, frames - start);
        if (end < minLength) return null;
        // pass 1: the gain. c, aa and rr over the frames until the fit gets worse than the best seen so far
        double c = 0;
        double aa = 0;
        double rr = 0;
        double bestReduction = 0;
        double bestC = 0;
        double bestA = 0;
        double bestR = 0;
        for (int t = 0; t < end; t++) {
            row(snd.entry, pitch, t, tmp);
            int at = (start + t) * B;
            for (int b = 0; b < B; b++) {
                float a = tmp[b];
                float v = r[at + b];
                float wb = w[b];
                c += wb * v * a;
                aa += wb * a * a;
                if (v > 0) rr += wb * v * v;
            }
            if (t + 1 >= minLength && c > 0 && aa > 0) {
                double reduction = c * c / aa;
                if (reduction > bestReduction) {
                    bestReduction = reduction;
                    bestC = c;
                    bestA = aa;
                    bestR = rr;
                }
            }
        }
        if (bestReduction <= 0 || bestR <= 0) return null;
        if (bestC / Math.sqrt(bestA * bestR) < s.minSimilarity) return null;
        float g = (float) Math.min(1, bestC / bestA);
        // pass 2: the cost drop with that gain, frame by frame; the best place to stop
        double drop = 0;
        double best = 0;
        int bestLength = -1;
        for (int t = 0; t < n; t++) {
            row(snd.entry, pitch, t, tmp);
            int frame = start + t;
            if (frame < frames) {
                int at = frame * B;
                for (int b = 0; b < B; b++) {
                    float v = r[at + b];
                    drop += w[b] * (cost(v) - cost(v - g * tmp[b]));
                }
            } else {
                for (int b = 0; b < B; b++) drop -= w[b] * overshoot * g * g * tmp[b] * tmp[b];
            }
            int l = t + 1;
            if (l < minLength) continue;
            boolean whole = l == n && !mustStop;
            boolean cut = !whole && (s.subtick || (start + l) % Spectra.FRAMES_PER_TICK == 0);
            if ((whole || cut) && drop > best) {
                best = drop;
                bestLength = l;
            }
        }
        if (bestLength < 0) return null;
        boolean stopped = bestLength < n || mustStop;
        double score = best * preference(snd.tonality, start, start + bestLength);
        return new Entry(atom, start, bestLength, stopped, g, score);
    }

    /** Step 4: the limits, before placing a sound that plays from start for length frames. */
    private boolean fits(int sound, int start, int length, int[] voices, int[] streams, int[] starts,
                         Map<Integer, List<Integer>> startsOf) {
        if (starts[start / Spectra.FRAMES_PER_TICK] >= s.maxPerTick) return false;
        boolean stream = sounds.get(sound).stream;
        for (int t = start; t < start + length && t < voices.length; t++) {
            if (voices[t] >= s.maxVoices) return false;
            if (stream && streams[t] >= s.maxStreams) return false;
        }
        List<Integer> mine = startsOf.get(sound);
        if (mine != null) {
            int window = s.rateTicks * Spectra.FRAMES_PER_TICK;
            List<Integer> near = new ArrayList<>();
            for (int x : mine) if (Math.abs(x - start) < window) near.add(x);
            near.add(start);
            near.sort(null);
            // the most starts inside any window shorter than `window` that holds this start
            for (int i = 0; i < near.size(); i++) {
                if (near.get(i) > start) break;
                int count = 0;
                for (int j = i; j < near.size() && near.get(j) - near.get(i) < window; j++) count++;
                if (count > s.rateCount) return false;
            }
        }
        return true;
    }

    private record Keys(List<Play> plays, List<SoundSeeds.Use> use, List<String> source, int dropped) {
    }

    /** Step 6: a sound id and a source per play, so no stop hits another play. */
    private Keys keys(List<Play> plays) {
        List<String> sources = new ArrayList<>(SoundSeeds.sources());
        sources.remove("master");
        sources.addFirst("master");
        List<Play> sorted = new ArrayList<>(plays);
        sorted.sort((a, b) -> Integer.compare(a.start, b.start));
        Map<String, List<Play>> onKey = new HashMap<>();
        List<Play> out = new ArrayList<>();
        List<SoundSeeds.Use> use = new ArrayList<>();
        List<String> source = new ArrayList<>();
        int dropped = 0;
        for (Play p : sorted) {
            boolean found = false;
            search:
            for (SoundSeeds.Use u : sounds.get(p.sound).uses) {
                for (String src : sources) {
                    String key = u.sound() + "|" + src;
                    List<Play> others = onKey.computeIfAbsent(key, x -> new ArrayList<>());
                    boolean clash = false;
                    for (int i = others.size() - 1; i >= 0; i--) {
                        Play o = others.get(i);
                        if (clashes(p, o) || clashes(o, p)) {
                            clash = true;
                            break;
                        }
                    }
                    if (clash) continue;
                    others.add(p);
                    out.add(p);
                    use.add(u);
                    source.add(src);
                    found = true;
                    break search;
                }
            }
            if (!found) dropped++;
        }
        return new Keys(out, use, source, dropped);
    }

    /** Does a's stop hit b while b plays? Stops are sent before starts of the same moment; 2 frames of slack. */
    private static boolean clashes(Play a, Play b) {
        if (!a.stopped || a == b) return false;
        int stop = a.start + a.length;
        return b.start < stop && stop < b.start + b.length + 2;
    }
}
