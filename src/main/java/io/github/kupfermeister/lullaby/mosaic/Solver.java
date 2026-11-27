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
 * The mosaic itself: which sound files start when, at which pitch and volume, and which are cut off.
 *
 * <p>The input and every sound are spectrograms (Spectra). R, the residual, starts as the input and is what is
 * still missing. A sound placed at frame s with gain g takes g times its spectrogram off R. Each placement is the one
 * that takes away the most (least squares, weighted per frequency: {@code weighting}), as long as it is similar
 * enough ({@code min_similarity}) and worth it ({@code min_gain}): greedy matching pursuit. "Lazy": a candidate's
 * score only gets worse when R changes under it, so a popped score is worked out again only if something was placed
 * over its frames since.
 *
 * <ol>
 *   <li>Screening (all cores): for every start time and every sound at every pitch, how well its first 40 ms fit;
 *       the best {@code candidates} per start time and length group are kept. This is the only step that looks at
 *       every sound, so it is kept short.
 *   <li>Passes, long to short ({@code lengths}): pass i places only sounds that play at least lengths[i] seconds.
 *       Each candidate's best length is found: the whole sound, or cut off earlier (stopsound) where the rest would
 *       not fit, with {@code cut_penalty}. Files longer than {@code max_sound_seconds} are always cut off by then.
 *   <li>Every score is multiplied by the melody preference: 1 + melody_weight * tonality * input tonality + noise_weight
 *       * (1 - tonality) * (1 - input tonality), so a tonal sound wins on tonal moments and a noisy one on noisy moments.
 *   <li>Limits checked before each placement: max_per_tick starts per tick, max_voices and max_streams playing at
 *       once (a sound plays its real length unless cut), the rate limit per file.
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
    private final int range;
    private final int pitches;
    private final int grid;
    private final int[] lengthFrames;

    private float[] r; // residual, frames x B
    private int frames;
    private float[] energy; // weighted energy of the (scaled) input per frame
    private float[] tonal; // input tonality per frame (smoothed)
    private double[] cumTonalEnergy;
    private double[] cumEnergy;

    Solver(Settings s, List<Sound> sounds, ForkJoinPool pool, Consumer<String> say) {
        this.s = s;
        this.sounds = sounds;
        this.w = Spectra.weights(s);
        this.pool = pool;
        this.say = say;
        this.range = s.pitchRange;
        this.pitches = 2 * range + 1;
        this.grid = s.subtick ? 1 : Spectra.FRAMES_PER_TICK;
        this.lengthFrames = new int[s.lengths.length];
        for (int i = 0; i < s.lengths.length; i++) {
            int f = (int) Math.round(s.lengths[i] * 100);
            if (!s.subtick) f = Math.max(grid, f / grid * grid);
            lengthFrames[i] = Math.max(1, f);
        }
    }

    // ---- a sound at a pitch ----

    private static final double[] RATES = new double[25];

    static {
        for (int i = 0; i < 25; i++) RATES[i] = Math.pow(2, (i - 12) / 12.0);
    }

    private static double rate(int step) {
        return RATES[step + 12];
    }

    /** Frames the stored part lasts at this pitch. */
    private static int framesAt(int stored, int step) {
        return (int) Math.floor((stored - 1) / rate(step)) + 1;
    }

    private int pitchedFrames(int sound, int step) {
        return framesAt(sounds.get(sound).entry.frames(), step);
    }

    /** Frames it plays when not cut off, -1 if longer than what is stored (then it must be cut). */
    private int realFrames(int sound, int step) {
        int real = sounds.get(sound).entry.realFrames();
        return real < 0 ? -1 : framesAt(real, step);
    }

    /** Row t of the sound at this pitch: frames squeezed by the rate, bins shifted by the steps. */
    private void row(Dictionary.Entry e, int step, int t, float[] out) {
        double src = t * rate(step);
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
        for (int b = 0; b < B; b++) {
            int sb = b - step;
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

    /** Which length group (index in lengths) a sound at a pitch belongs to, -1 if shorter than the shortest. */
    private int group(int sound, int step) {
        int usable = pitchedFrames(sound, step);
        for (int i = 0; i < lengthFrames.length; i++) if (usable >= lengthFrames[i]) return i;
        return -1;
    }

    // ---- solving ----

    Result solve(float[] input, int inputFrames) throws Exception {
        frames = inputFrames;
        prepare(input);
        int positions = (frames + grid - 1) / grid;
        int groups = lengthFrames.length;
        int k = s.candidates;
        int[] candAtom = new int[positions * groups * k];
        float[] candScore = new float[positions * groups * k];
        Arrays.fill(candAtom, -1);
        // FORMAT: mosaic progress
        say.accept("comparing " + sounds.size() + " sounds x " + pitches + " pitches at " + positions + " start times");
        screen(positions, groups, k, candAtom, candScore);

        double average = 0;
        int loud = 0;
        for (int t = 0; t < frames; t++) {
            if (energy[t] > 1e-12) {
                average += energy[t];
                loud++;
            }
        }
        average = loud > 0 ? average / loud : 0;
        double threshold = Math.max(1e-12, s.minGain * average);
        double total = 0;
        for (int t = 0; t < frames; t++) total += energy[t];

        int margin = 0;
        for (int i = 0; i < sounds.size(); i++) margin = Math.max(margin, framesAt(sounds.get(i).entry.frames(), -range) + 2);
        int[] voices = new int[frames + margin + 1];
        int[] streams = new int[frames + margin + 1];
        int[] starts = new int[frames / Spectra.FRAMES_PER_TICK + 2];
        int[] modified = new int[frames];
        Map<Integer, List<Integer>> startsOf = new HashMap<>();
        List<Play> plays = new ArrayList<>();
        int step = 0;
        StringBuilder report = new StringBuilder();

        for (int pass = 0; pass < groups; pass++) {
            final int minLength = lengthFrames[pass];
            final int passNo = pass;
            // every candidate of the groups long enough for this pass, scored on the residual as it is now
            List<Entry> entries = pool.submit(() -> IntStream.range(0, positions).parallel().mapToObj(pos -> {
                List<Entry> out = new ArrayList<>();
                float[] tmp = new float[B];
                for (int g = 0; g <= passNo; g++) {
                    for (int c = 0; c < k; c++) {
                        int atom = candAtom[(pos * groups + g) * k + c];
                        if (atom < 0) continue;
                        Entry e = evaluate(atom, pos * grid, minLength, tmp);
                        if (e != null && e.score >= threshold) out.add(e);
                    }
                }
                return out;
            }).flatMap(List::stream).toList()).get();
            for (Entry e : entries) e.step = step; // scored on R as it is now
            PriorityQueue<Entry> heap = new PriorityQueue<>((a, b) -> Double.compare(b.score, a.score));
            heap.addAll(entries);
            float[] tmp = new float[B];
            int placed = 0;
            while (!heap.isEmpty()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Entry e = heap.poll();
                if (e.score < threshold) break;
                int sound = e.atom / pitches;
                int pitchStep = e.atom % pitches - range;
                int span = Math.min(frames, e.start + pitchedFrames(sound, pitchStep));
                boolean stale = false;
                for (int t = e.start; t < span; t++) {
                    if (modified[t] > e.step) {
                        stale = true;
                        break;
                    }
                }
                if (stale) {
                    Entry again = evaluate(e.atom, e.start, minLength, tmp);
                    if (again != null && again.score >= threshold) {
                        again.step = step;
                        heap.add(again);
                    }
                    continue;
                }
                int plays_ = e.stopped ? e.length : realFrames(sound, pitchStep);
                if (!fits(sound, e.start, plays_, voices, streams, starts, startsOf)) continue;
                // place it
                Dictionary.Entry de = sounds.get(sound).entry;
                for (int t = 0; t < e.length && e.start + t < frames; t++) {
                    row(de, pitchStep, t, tmp);
                    int at = (e.start + t) * B;
                    for (int b = 0; b < B; b++) r[at + b] -= e.gain * tmp[b];
                    modified[e.start + t] = step + 1;
                }
                step++;
                for (int t = e.start; t < e.start + plays_; t++) {
                    voices[t]++;
                    if (sounds.get(sound).stream) streams[t]++;
                }
                starts[e.start / Spectra.FRAMES_PER_TICK]++;
                startsOf.computeIfAbsent(sound, x -> new ArrayList<>()).add(e.start);
                plays.add(new Play(sound, rate(pitchStep), e.start, plays_, e.stopped, e.gain));
                placed++;
            }
            // FORMAT: mosaic pass report
            String line = "pass " + (pass + 1) + " (sounds of " + s.lengths[pass] + " s and longer): " + placed + " placed";
            say.accept(line);
            report.append(line).append('\n');
        }

        double left = 0;
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                left += w[b] * v * v;
            }
        }
        Keys keys = keys(plays);
        int maxVoices = 0;
        for (int v : voices) maxVoices = Math.max(maxVoices, v);
        long cut = keys.plays.stream().filter(Play::stopped).count();
        // FORMAT: mosaic result summary
        String summary = keys.plays.size() + " sounds (" + cut + " cut off), at most " + maxVoices
                + " at once, explains " + Math.round(Math.max(0, 100 * (1 - left / Math.max(1e-12, total)))) + "% of the input"
                + (keys.dropped > 0 ? "; " + keys.dropped + " cut-off sounds left out (no free stop key)" : "");
        report.append(summary).append('\n');
        return new Result(keys.plays, keys.use, keys.source, report.toString());
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
        tonal = new float[frames];
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

    /** Step 1: the best few sounds per start time and length group, by how well their first 40 ms fit. */
    private void screen(int positions, int groups, int k, int[] candAtom, float[] candScore) throws Exception {
        float[] rw = new float[r.length];
        for (int t = 0; t < frames; t++) for (int b = 0; b < B; b++) rw[t * B + b] = r[t * B + b] * w[b];
        int chunks = Math.max(1, Math.min(positions, pool.getParallelism() * 4));
        pool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk -> {
            int from = (int) ((long) positions * chunk / chunks);
            int to = (int) ((long) positions * (chunk + 1) / chunks);
            float[] head = new float[HEAD * B];
            float[] tmp = new float[B];
            boolean[] quiet = new boolean[to - from];
            for (int pos = from; pos < to; pos++) {
                int st = pos * grid;
                double e = cumEnergy[Math.min(frames, st + HEAD)] - cumEnergy[st];
                quiet[pos - from] = e <= 1e-12;
            }
            for (int sound = 0; sound < sounds.size(); sound++) {
                Sound snd = sounds.get(sound);
                for (int st = -range; st <= range; st++) {
                    int group = group(sound, st);
                    if (group < 0) continue;
                    int len = Math.min(HEAD, pitchedFrames(sound, st));
                    double aw = 0;
                    for (int t = 0; t < len; t++) {
                        row(snd.entry, st, t, tmp);
                        System.arraycopy(tmp, 0, head, t * B, B);
                        for (int b = 0; b < B; b++) aw += w[b] * tmp[b] * tmp[b];
                    }
                    if (aw <= 0) continue;
                    int atom = sound * pitches + st + range;
                    for (int pos = from; pos < to; pos++) {
                        if (quiet[pos - from]) continue;
                        int start = pos * grid;
                        int n = Math.min(len, frames - start);
                        double c = 0;
                        for (int t = 0; t < n; t++) {
                            int at = (start + t) * B;
                            int ht = t * B;
                            for (int b = 0; b < B; b++) c += rw[at + b] * head[ht + b];
                        }
                        if (c <= 0) continue;
                        double g = Math.min(1, c / aw);
                        double score = (2 * g * c - g * g * aw) * preference(snd.tonality, start, start + len);
                        int base = (pos * groups + group) * k;
                        // keep the best k: replace the weakest
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

    /** A candidate placement with its best length. */
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

    /** Step 2: the best length (whole, or cut off at least minLength long) of one sound at one start, on R now. */
    private Entry evaluate(int atom, int start, int minLength, float[] tmp) {
        int sound = atom / pitches;
        int pitchStep = atom % pitches - range;
        Sound snd = sounds.get(sound);
        int n = pitchedFrames(sound, pitchStep);
        boolean mustStop = snd.entry.realFrames() < 0;
        double c = 0;
        double aa = 0;
        double rr = 0;
        Entry best = null;
        double minLog = lengthFrames[lengthFrames.length - 1];
        for (int t = 0; t < n; t++) {
            row(snd.entry, pitchStep, t, tmp);
            int frame = start + t;
            if (frame < frames) {
                int at = frame * B;
                for (int b = 0; b < B; b++) {
                    float a = tmp[b];
                    float v = r[at + b];
                    float wb = w[b];
                    c += wb * v * a;
                    aa += wb * a * a;
                    if (v > 0) rr += wb * v * v;
                }
            } else {
                for (int b = 0; b < B; b++) aa += w[b] * tmp[b] * tmp[b];
            }
            int l = t + 1;
            if (l < minLength) continue;
            boolean whole = l == n && !mustStop;
            boolean cut = !whole && (s.subtick || (start + l) % Spectra.FRAMES_PER_TICK == 0);
            if (!whole && !cut) continue;
            if (c <= 0 || aa <= 0 || rr <= 0) continue;
            if (c / Math.sqrt(aa * rr) < s.minSimilarity) continue;
            double g = Math.min(1, c / aa);
            double reduction = 2 * g * c - g * g * aa;
            double score = reduction * preference(snd.tonality, start, start + l)
                    * (whole ? 1 : 1 - s.cutPenalty)
                    * (1 + s.lengthBonus * Math.log(l / minLog) / Math.log(2));
            if (best == null || score > best.score) best = new Entry(atom, start, l, !whole, (float) g, score);
        }
        return best;
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

    /** Step 5: a sound id and a source per play, so no stop hits another play. */
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
                    for (Play o : others) {
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
