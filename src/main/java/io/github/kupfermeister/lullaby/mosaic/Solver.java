package io.github.kupfermeister.lullaby.mosaic;

import io.github.kupfermeister.lullaby.nms.SoundSeeds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.IntStream;

/**
 * The mosaic itself: which sound files start when, at which pitch and volume, and where each is cut off.
 *
 * <p>The input and every sound are spectrograms (Spectra). R, the residual, starts as the input and is what is
 * still missing. A sound placed at frame s with gain g takes g times its spectrogram off R. The cost of R: missing
 * sound (R above 0) and added sound (R below 0, sound the input doesn't have) each weighted per frequency; added
 * sound counts {@code overshoot_penalty} times. Each placement is the one that lowers the cost the most (greedy
 * matching pursuit), if similar enough ({@code min_similarity}) and worth it ({@code min_gain}). "Lazy": a
 * candidate's score only gets worse when R changes under it, so a popped score is worked out again only if
 * something was placed over its frames since.
 *
 * <p>Layers, in this order, each with its own rounds (every round: screening, then placing):
 * <ol>
 *   <li>melody: where the input holds one clear tone, only sounds whose first note is that tone (half a semitone)
 *       and that stay tonal for the length they play;
 *   <li>bass: only what is missing below bass_below_hz counts, only clean bass sounds (bass purity);
 *   <li>main: everything;
 *   <li>detail: only what is missing above detail_from_hz counts, less strict, at most detail_volume.
 * </ol>
 * Screening: for every start time and every sound at every pitch, how well its first 40 ms (more with precision)
 * fit; the best candidates per start time are kept. Each candidate's length: a sound at least
 * cut_only_longer_than long plays as long as it keeps lowering the cost and is then cut off (stopsound), shorter sounds
 * play whole; nothing plays longer than max_play_seconds. Limits before each placement: max_per_tick, max_voices,
 * max_streams, the rate limit per file. Stop keys at the end: every play gets a sound id and source (volume slider)
 * such that no stop hits another play. Runs on the mosaic job thread and its pool; plain data only.
 */
final class Solver {

    /** A sound file usable here: its analysis, tonality override (or null) and the sound ids that can play it. */
    record Sound(Dictionary.Entry entry, Double override, List<SoundSeeds.Use> uses, boolean stream) {
    }

    /** One placed sound. pitch: real playback rate (0.5..2); length: frames it plays; stopped: cut off then. */
    record Play(int sound, double pitch, int start, int length, boolean stopped, float gain) {
    }

    /** The finished mosaic: plays with their keys, and numbers for the report. */
    record Result(List<Play> plays, List<SoundSeeds.Use> use, List<String> source, String report) {
    }

    private static final int B = Spectra.BINS;

    private enum Kind { MELODY, BASS, MAIN, DETAIL }

    /** One layer: what counts as missing, how strict, how loud at most. */
    private record Layer(Kind kind, String name, int rounds, float[] wMiss, float[] wOver, double minSimilarity,
                         float gainCap) {
    }

    private final Settings s;
    private final List<Sound> sounds;
    private final Features[] features;
    private final float[] w;
    private final ForkJoinPool pool;
    private final Consumer<String> say;
    private final int pitches;
    private final double[] rateOf; // pitch index -> playback rate
    private final int[] shiftOf; // pitch index -> bins the spectrum moves
    private final int grid;
    private final int minLength;
    private final int maxPlay;
    private final int head;
    private final int candidates;
    private final double minGain;

    private float[] r; // residual, frames x B
    private int frames;
    private double[] cumTonalEnergy;
    private double[] cumEnergy;
    private int[] inputPitch;
    private boolean[] melodic;

    // what all layers share
    private int[] voices, streams, starts, modified;
    private final Map<Integer, List<Integer>> startsOf = new HashMap<>();
    private final List<Play> plays = new ArrayList<>();
    private int step;

    Solver(Settings s, List<Sound> sounds, Features[] features, ForkJoinPool pool, Consumer<String> say) {
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
        this.grid = s.subtick ? 1 : Spectra.FRAMES_PER_TICK;
        this.minLength = frames(s.minLength, true);
        this.maxPlay = Math.max(1, frames(s.maxPlaySeconds, false));
        this.head = Math.min(12, 4 * s.precision);
        this.candidates = s.candidates * s.precision;
        this.minGain = s.minGain / s.saturation;
        this.features = features;
    }

    /** Seconds as frames; on the tick grid unless subtick (up for a minimum, down for a maximum). */
    private int frames(double seconds, boolean up) {
        int f = (int) Math.round(seconds * 100);
        if (!s.subtick) f = up ? Math.max(grid, (f + grid - 1) / grid * grid) : Math.max(grid, f / grid * grid);
        return Math.max(1, f);
    }

    /** How strict a layer may be at this saturation: thresholds go down (up) as saturation goes up (down). */
    private double similarity(double base) {
        return Math.min(1, base / Math.sqrt(s.saturation));
    }

    private float overshoot(double base) {
        return (float) (1 + (base - 1) / Math.sqrt(s.saturation));
    }

    /** How tonal the sound is up to this many frames played at this pitch (0..1). */
    private float tonality(int sound, int pitch, int length) {
        return features[sound].tonality((int) Math.ceil(length * rateOf[pitch]), sounds.get(sound).override);
    }

    // ---- a sound at a pitch ----

    private int framesAt(int stored, int pitch) {
        return (int) Math.floor((stored - 1) / rateOf[pitch]) + 1;
    }

    /** Frames it plays when not cut off, -1 if longer than what is stored. */
    private int realFrames(int sound, int pitch) {
        int real = sounds.get(sound).entry.realFrames();
        return real < 0 ? -1 : framesAt(real, pitch);
    }

    /** May be cut off (Features: it hits again or holds its loudness); else it always plays whole. */
    private boolean mayCut(int sound, int pitch) {
        return features[sound].stoppable;
    }

    /** A stoppable sound longer than max_play_seconds: must be cut off by then. */
    private boolean tooLong(int sound, int pitch) {
        if (!mayCut(sound, pitch)) return false;
        int real = realFrames(sound, pitch);
        return real < 0 || real > maxPlay;
    }

    /** The frames that can play: a single hit whole, a stoppable sound at most max_play_seconds. */
    private int horizon(int sound, int pitch) {
        int stored = framesAt(sounds.get(sound).entry.frames(), pitch);
        int real = realFrames(sound, pitch);
        if (!mayCut(sound, pitch)) return real < 0 ? stored : Math.min(stored, real);
        return tooLong(sound, pitch) ? Math.min(stored, maxPlay) : Math.min(stored, real);
    }

    private boolean usable(int sound, int pitch) {
        int n = horizon(sound, pitch);
        return mayCut(sound, pitch) ? n >= minLength : n >= 1 && realFrames(sound, pitch) >= 0;
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

    // ---- solving ----

    Result solve(float[] input, int inputFrames) throws Exception {
        frames = inputFrames;
        double total = prepare(input);
        int margin = 0;
        for (int i = 0; i < sounds.size(); i++) margin = Math.max(margin, framesAt(sounds.get(i).entry.frames(), 0) + 2);
        voices = new int[frames + margin + 1];
        streams = new int[frames + margin + 1];
        starts = new int[frames / Spectra.FRAMES_PER_TICK + 2];
        modified = new int[frames];

        float[] over = new float[B];
        float[] none = new float[B];
        float[] low = new float[B];
        float[] high = new float[B];
        float[] detailOver = new float[B];
        float mainOver = overshoot(s.overshoot);
        for (int b = 0; b < B; b++) {
            double f = Spectra.frequency(b);
            over[b] = w[b] * mainOver;
            low[b] = f < s.bassBelowHz ? Math.max(1, w[b]) : 0;
            high[b] = f >= s.detailFromHz ? w[b] : 0;
            detailOver[b] = w[b] * overshoot(s.detailOvershoot);
        }
        List<Layer> layers = new ArrayList<>();
        int p = s.precision;
        // FORMAT: mosaic layer names
        if (s.melodyLayer) layers.add(new Layer(Kind.MELODY, "melody", s.melodyRounds * p, w, over, similarity(s.minSimilarity), 1));
        if (s.bassLayer) layers.add(new Layer(Kind.BASS, "bass", s.bassRounds * p, low, over, similarity(s.minSimilarity), 1));
        layers.add(new Layer(Kind.MAIN, "main", s.rounds * p, w, over, similarity(s.minSimilarity), 1));
        if (s.detailLayer) layers.add(new Layer(Kind.DETAIL, "detail", s.detailRounds * p, high, detailOver,
                similarity(s.detailSimilarity), (float) s.detailVolume));
        StringBuilder report = new StringBuilder();
        for (Layer layer : layers) {
            int before = plays.size();
            run(layer);
            // FORMAT: mosaic layer report
            String line = layer.name + ": " + (plays.size() - before) + " placed, explains " + explained(total) + "%";
            say.accept(line);
            report.append(line).append('\n');
        }

        Shared.Keys keys = Shared.keys(plays, sounds);
        int maxVoices = 0;
        for (int v : voices) maxVoices = Math.max(maxVoices, v);
        long cut = keys.plays().stream().filter(Play::stopped).count();
        // FORMAT: mosaic result summary
        String summary = keys.plays().size() + " sounds (" + cut + " cut off), at most " + maxVoices
                + " at once, explains " + explained(total) + "% of the input"
                + (keys.dropped() > 0 ? "; " + keys.dropped() + " cut-off sounds left out (no free stop key)" : "");
        report.append(summary).append('\n');
        return new Result(keys.plays(), keys.use(), keys.source(), report.toString());
    }

    private void run(Layer layer) throws Exception {
        int positions = (frames + grid - 1) / grid;
        int k = candidates;
        int[] candAtom = new int[positions * k];
        float[] candScore = new float[positions * k];
        // the threshold: min_gain of an average frame's energy as this layer counts it
        double average = 0;
        int loud = 0;
        for (int t = 0; t < frames; t++) {
            double e = 0;
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                if (v > 0) e += layer.wMiss[b] * v * v;
            }
            if (e > 1e-12) {
                average += e;
                loud++;
            }
        }
        if (loud == 0) return;
        double threshold = Math.max(1e-12, minGain * average / loud);
        float[] tmp = new float[B];
        for (int round = 0; round < layer.rounds; round++) {
            // FORMAT: mosaic progress
            say.accept(layer.name + " round " + (round + 1) + ": comparing " + sounds.size() + " sounds x " + pitches
                    + " pitches at " + positions + " start times");
            Arrays.fill(candAtom, -1);
            Arrays.fill(candScore, 0);
            screen(layer, positions, k, candAtom, candScore);
            List<Entry> entries = pool.submit(() -> IntStream.range(0, positions).parallel().mapToObj(pos -> {
                List<Entry> out = new ArrayList<>();
                float[] buf = new float[B];
                for (int c = 0; c < k; c++) {
                    int atom = candAtom[pos * k + c];
                    if (atom < 0) continue;
                    Entry e = evaluate(layer, atom, pos * grid, buf);
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
                int span = Math.min(frames, e.start + horizon(sound, pitch));
                boolean stale = false;
                for (int t = e.start; t < span; t++) {
                    if (modified[t] > e.step) {
                        stale = true;
                        break;
                    }
                }
                if (stale) {
                    Entry again = evaluate(layer, e.atom, e.start, tmp);
                    if (again != null && again.score >= threshold) {
                        again.step = step;
                        heap.add(again);
                    }
                    continue;
                }
                int playing = e.stopped ? e.length : realFrames(sound, pitch);
                if (!fits(sound, e.start, playing)) continue;
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
            if (placed < Math.max(3, plays.size() / 100)) break; // nothing much left to find
        }
    }

    /** How much of the input's cost is gone, in percent (added sound counted with the main penalty). */
    private long explained(double total) {
        float over = overshoot(s.overshoot);
        double left = 0;
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                left += w[b] * (v >= 0 ? v * v : over * v * v);
            }
        }
        return Math.round(Math.max(0, 100 * (1 - left / Math.max(1e-12, total))));
    }

    /** Scales the input to the sounds' loudness; its tonality and pitch per frame. Returns its weighted energy. */
    private double prepare(float[] input) {
        r = input.clone();
        Shared.scale(r, frames, sounds, w, s.loudness);
        double[] energy = new double[frames];
        double total = 0;
        double loudest = 0;
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) energy[t] += w[b] * (double) r[t * B + b] * r[t * B + b];
            total += energy[t];
            double plain = 0;
            for (int b = 0; b < B; b++) plain += (double) r[t * B + b] * r[t * B + b];
            loudest = Math.max(loudest, plain);
        }
        float[] per = Spectra.tonalityPerFrame(r, frames);
        float[] tonal = new float[frames];
        inputPitch = new int[frames];
        melodic = new boolean[frames];
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
            double plain = 0;
            for (int b = 0; b < B; b++) plain += (double) r[t * B + b] * r[t * B + b];
            inputPitch[t] = Features.pitchOf(r, t * B, per[t], plain, loudest);
            melodic[t] = tonal[t] >= 0.5 && inputPitch[t] != Features.NONE;
        }
        cumEnergy = new double[frames + 1];
        cumTonalEnergy = new double[frames + 1];
        for (int t = 0; t < frames; t++) {
            cumEnergy[t + 1] = cumEnergy[t] + energy[t];
            cumTonalEnergy[t + 1] = cumTonalEnergy[t] + energy[t] * tonal[t];
        }
        return total;
    }

    /** May this sound at this pitch start here in this layer at all (before any scoring)? */
    private boolean allowed(Layer layer, int sound, int pitch, int start) {
        return switch (layer.kind) {
            case MELODY -> {
                Features f = features[sound];
                if (f.note == Features.NONE || tonality(sound, pitch, Math.max(minLength, 10)) < s.melodyTonality) yield false;
                int note = f.note + shiftOf[pitch];
                boolean hit = false;
                for (int t = start; t < Math.min(frames, start + head); t++) {
                    if (melodic[t] && Math.abs(inputPitch[t] - note) <= 1) {
                        hit = true;
                        break;
                    }
                }
                yield hit;
            }
            case BASS -> features[sound].purity >= s.bassPurity;
            default -> true;
        };
    }

    /** The best few sounds per start time, by how well their first frames fit R as it is now. */
    private void screen(Layer layer, int positions, int k, int[] candAtom, float[] candScore) throws Exception {
        float[] rw = new float[r.length];
        float[] missing = new float[frames + 1]; // cumulative energy still missing, as this layer counts it
        for (int t = 0; t < frames; t++) {
            double m = 0;
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                rw[t * B + b] = v * (v > 0 ? layer.wMiss[b] : layer.wOver[b]);
                if (v > 0) m += layer.wMiss[b] * v * v;
            }
            missing[t + 1] = (float) (missing[t] + m);
        }
        int chunks = Math.max(1, Math.min(positions, pool.getParallelism() * 4));
        pool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk -> {
            int from = (int) ((long) positions * chunk / chunks);
            int to = (int) ((long) positions * (chunk + 1) / chunks);
            float[] headRows = new float[head * B];
            float[] tmp = new float[B];
            boolean[] quiet = new boolean[to - from];
            for (int pos = from; pos < to; pos++) {
                int st = pos * grid;
                quiet[pos - from] = missing[Math.min(frames, st + head)] - missing[st] <= 1e-12;
            }
            for (int sound = 0; sound < sounds.size(); sound++) {
                Dictionary.Entry entry = sounds.get(sound).entry;
                for (int p = 0; p < pitches; p++) {
                    if (!usable(sound, p)) continue;
                    if (layer.kind == Kind.BASS && features[sound].purity < s.bassPurity) continue;
                    int len = Math.min(head, horizon(sound, p));
                    double aw = 0;
                    for (int t = 0; t < len; t++) {
                        row(entry, p, t, tmp);
                        System.arraycopy(tmp, 0, headRows, t * B, B);
                        for (int b = 0; b < B; b++) aw += layer.wMiss[b] * tmp[b] * tmp[b];
                    }
                    if (aw <= 0) continue;
                    float tone = tonality(sound, p, len);
                    int atom = sound * pitches + p;
                    for (int pos = from; pos < to; pos++) {
                        if (quiet[pos - from]) continue;
                        int start = pos * grid;
                        if (layer.kind == Kind.MELODY && !allowed(layer, sound, p, start)) continue;
                        int n = Math.min(len, frames - start);
                        // four separate sums: the CPU works on them side by side (the hottest loop of all)
                        float c0 = 0, c1 = 0, c2 = 0, c3 = 0;
                        for (int t = 0; t < n; t++) {
                            int at = (start + t) * B;
                            int ht = t * B;
                            for (int b = 0; b < B; b += 4) {
                                c0 += rw[at + b] * headRows[ht + b];
                                c1 += rw[at + b + 1] * headRows[ht + b + 1];
                                c2 += rw[at + b + 2] * headRows[ht + b + 2];
                                c3 += rw[at + b + 3] * headRows[ht + b + 3];
                            }
                        }
                        double c = (double) c0 + c1 + c2 + c3;
                        if (c <= 0) continue;
                        double g = Math.min(layer.gainCap, c / aw);
                        double score = (2 * g * c - g * g * aw) * preference(tone, start, start + len);
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
     * Strict scoring of one sound at one start: the gain from least squares over the part that fits (at most the
     * layer's gain cap), then the cost drop frame by frame with that gain; it plays up to the frame where the total
     * drop is highest (whole if it may not be cut), so it is cut off as soon as the rest would only add what the
     * input doesn't have. Melody: only lengths that stay tonal enough.
     */
    private Entry evaluate(Layer layer, int atom, int start, float[] tmp) {
        int sound = atom / pitches;
        int pitch = atom % pitches;
        Sound snd = sounds.get(sound);
        if (!usable(sound, pitch) || !allowed(layer, sound, pitch, start)) return null;
        int n = horizon(sound, pitch);
        boolean mustStop = tooLong(sound, pitch);
        boolean cuttable = mayCut(sound, pitch);
        int shortest = cuttable ? minLength : n;
        int end = Math.min(n, frames - start);
        float[] wm = layer.wMiss;
        float[] wo = layer.wOver;
        // pass 1: the gain
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
                c += wm[b] * v * a;
                aa += wm[b] * a * a;
                if (v > 0) rr += wm[b] * v * v;
            }
            if (t + 1 >= Math.min(shortest, end) && c > 0 && aa > 0) {
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
        if (bestC / Math.sqrt(bestA * bestR) < layer.minSimilarity) return null;
        float g = (float) Math.min(layer.gainCap, bestC / bestA);
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
                    float after = v - g * tmp[b];
                    drop += (v > 0 ? wm[b] * v * v : wo[b] * v * v) - (after > 0 ? wm[b] * after * after : wo[b] * after * after);
                }
            } else {
                for (int b = 0; b < B; b++) drop -= wo[b] * g * g * tmp[b] * tmp[b];
            }
            int l = t + 1;
            boolean whole = l == n && !mustStop;
            boolean cut = cuttable && l >= minLength && (l < n || mustStop)
                    && (s.subtick || (start + l) % Spectra.FRAMES_PER_TICK == 0);
            if (!whole && !cut) continue;
            if (layer.kind == Kind.MELODY && tonality(sound, pitch, l) < s.melodyTonality) continue;
            if (drop > best) {
                best = drop;
                bestLength = l;
            }
        }
        if (bestLength < 0) return null;
        boolean stopped = bestLength < n || mustStop;
        double score = best * preference(tonality(sound, pitch, bestLength), start, start + bestLength);
        return new Entry(atom, start, bestLength, stopped, g, score);
    }

    /** The limits, before placing a sound that plays from start for length frames. */
    private boolean fits(int sound, int start, int length) {
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

}
