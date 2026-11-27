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
 * The second mosaic method ({@code method = "slices"}): time is cut into slices of {@code slice_ms} (one tick, or
 * down to 10 ms with subtick), solved one after the other. The input is split into three parts, each its own layer
 * with its own volume ({@code volume = { melody, bass, noise }}; 0 turns one off):
 * <ul>
 *   <li>melody: the tonal part (harmonic/percussive split: what holds still over time against what holds still over
 *       the frequencies) above bass_below_hz, widened by melody_priority (noise keeps only what is clearly noisy). Only clean sounds (at least melody_tonality tonal, Features) whose
 *       first note is in key right now: the input's melody part holds that note (its harmonics) at least half as
 *       strongly as its strongest note. A melody sound goes on as long as its note stays in key in the next slice,
 *       then it is stopped: fluid, not cut by every small mismatch;
 *   <li>bass: everything below bass_below_hz, only clean bass sounds (bass_purity); like melody but without the key:
 *       a bass sound goes on while it still lowers the cost;
 *   <li>noise: the rest above bass_below_hz, all sounds; a sound goes on while it still lowers the cost.
 * </ul>
 * In every layer, new sounds start where they lower the cost the most (strict: added sound counts strictness times),
 * best first, while they fit; candidates per slice come from screening (every sound at every pitch, best
 * slice_candidates kept). A sound that may not be stopped (a single hit, Features) plays whole once started. Limits
 * (voices, per tick, streams, rate limit) are shared by all layers, melody first. Runs on the mosaic job thread and
 * its pool; plain data only.
 */
final class Slicer {

    private static final int B = Spectra.BINS;
    private static final double KEY = 0.5; // in key: at least half as strong as the strongest note right now

    private enum Kind { MELODY, BASS, NOISE, DETAIL }

    /** One layer: its own target (what is still missing of its part), weights, candidates and playing sounds. */
    private final class Layer {
        final Kind kind;
        final String name;
        final float[] r;
        final float[] wMiss;
        float[] overW = over; // added sound, weighted per bin (strictness)
        float gainCap = 1;
        double similarity = minSimilarity;
        float[] screenOn; // what screening compares with (null: r); the parts this layer may be handed later
        Layer handTo; // what this layer can't explain in a slice is handed on to that layer...
        boolean[] handBins; // ...in these bins
        double total;
        double threshold;
        int[] candAtom;
        float[] candScore;
        final List<Active> active = new ArrayList<>();
        int placed;

        Layer(Kind kind, String name, float[] r, float[] wMiss) {
            this.kind = kind;
            this.name = name;
            this.r = r;
            this.wMiss = wMiss;
        }

        float cost(float v, int b) {
            return v > 0 ? wMiss[b] * v * v : overW[b] * v * v;
        }

        /** May this sound start in this layer at all (before any scoring)? */
        boolean takes(int sound, int pitch, int slice) {
            return switch (kind) {
                case MELODY -> features[sound].note != Features.NONE
                        && tonality(sound, pitch, Slicer.this.slice) >= s.melodyTonality
                        && inKey(features[sound].note + shiftOf[pitch], slice);
                case BASS -> features[sound].purity >= s.bassPurity;
                case NOISE, DETAIL -> true;
            };
        }
    }

    private final Settings s;
    private final List<Solver.Sound> sounds;
    private final Features[] features;
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

    private int frames;
    private float[] keyStrength; // per frame and bin: how strongly the melody part holds that note
    private float[] keyMax; // per frame: the strongest note

    Slicer(Settings s, List<Solver.Sound> sounds, Features[] features, ForkJoinPool pool, Consumer<String> say) {
        this.s = s;
        this.sounds = sounds;
        this.features = features;
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
        // subtick: slices of slice_ms (10 ms steps); else every slice is one tick
        this.slice = s.subtick ? Math.max(1, (int) Math.round(s.sliceMs / 10.0)) : Spectra.FRAMES_PER_TICK;
        this.maxPlay = Math.max(slice, (int) Math.round(s.maxPlaySeconds * 100));
        this.candidates = s.sliceCandidates * s.precision;
        this.minSimilarity = Math.min(1, s.minSimilarity / Math.sqrt(s.saturation));
        float o = (float) (1 + (s.overshoot - 1) / Math.sqrt(s.saturation));
        this.over = new float[B];
        for (int b = 0; b < B; b++) over[b] = w[b] * o;
        this.minGain = s.minGain / s.saturation;
    }

    // ---- a sound at a pitch ----

    private int framesAt(int stored, int pitch) {
        return (int) Math.floor((stored - 1) / rateOf[pitch]) + 1;
    }

    private int realFrames(int sound, int pitch) {
        int real = sounds.get(sound).entry().realFrames();
        return real < 0 ? -1 : framesAt(real, pitch);
    }

    /** May be stopped (Features); else it plays whole once started. */
    private boolean stoppable(int sound) {
        return features[sound].stoppable;
    }

    /** Frames it can play: a single hit whole, a stoppable sound at most max_play_seconds. */
    private int horizon(int sound, int pitch) {
        int stored = framesAt(sounds.get(sound).entry().frames(), pitch);
        int real = realFrames(sound, pitch);
        if (!stoppable(sound)) return real < 0 ? stored : Math.min(stored, real);
        return Math.min(real < 0 ? stored : Math.min(stored, real), maxPlay);
    }

    /** Ends by itself within its horizon (else it must be stopped there)? */
    private boolean endsByItself(int sound, int pitch) {
        int real = realFrames(sound, pitch);
        return real >= 0 && real <= horizon(sound, pitch);
    }

    private boolean usable(int sound, int pitch) {
        return stoppable(sound) || realFrames(sound, pitch) >= 0;
    }

    private float tonality(int sound, int pitch, int length) {
        return features[sound].tonality((int) Math.ceil(length * rateOf[pitch]), sounds.get(sound).override());
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

    /** Is the note (bin) in key for most frames of this slice? */
    private boolean inKey(int note, int slice) {
        if (note < 0 || note >= B) return false;
        int from = slice * this.slice;
        int to = Math.min(frames, from + this.slice);
        int yes = 0;
        for (int t = from; t < to; t++) {
            float max = keyMax[t];
            if (max > 1e-9f && keyStrength[t * B + note] >= KEY * max) yes++;
        }
        return to > from && yes * 2 > to - from;
    }

    /** A sound playing, started in an earlier slice. */
    private static final class Active {
        final int sound, pitch, start;
        final float gain;
        int length;

        Active(int sound, int pitch, int start, float gain, int length) {
            this.sound = sound;
            this.pitch = pitch;
            this.start = start;
            this.gain = gain;
            this.length = length;
        }
    }

    // ---- solving ----

    Solver.Result solve(float[] input, int inputFrames) throws Exception {
        frames = inputFrames;
        float[] all = input.clone();
        Shared.scale(all, frames, sounds, w, s.loudness);
        float[] harmonic = harmonicShare(all);
        // melody_priority: noise keeps only the clearly noisy share, (1 - harmonic) ^ (1 / (1 - melody_priority)),
        // the melody gets the rest (0: the plain split; 0.75: noise keeps a bin only when it is far more noise than tone)
        double power = 1 / (1 - s.melodyPriority);
        for (int i = 0; i < harmonic.length; i++) harmonic[i] = (float) (1 - Math.pow(1 - harmonic[i], power));
        float[] mel = new float[all.length];
        float[] bass = new float[all.length];
        float[] noise = new float[all.length];
        float[] wBass = new float[B];
        for (int b = 0; b < B; b++) wBass[b] = Spectra.frequency(b) < s.bassBelowHz ? Math.max(1, w[b]) : w[b];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                int i = t * B + b;
                if (Spectra.frequency(b) < s.bassBelowHz) {
                    bass[i] = (float) (all[i] * s.volBass);
                } else {
                    mel[i] = (float) (all[i] * harmonic[i] * s.volMelody);
                    noise[i] = (float) (all[i] * (1 - harmonic[i]) * s.volNoise);
                }
            }
        }
        // the key: how strongly the melody part holds each note
        keyStrength = new float[all.length];
        keyMax = new float[frames];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                float v = (float) Features.salience(mel, t * B, b);
                keyStrength[t * B + b] = v;
                keyMax[t] = Math.max(keyMax[t], v);
            }
        }
        List<Layer> layers = new ArrayList<>();
        // FORMAT: mosaic slice layer names
        Layer melody = s.volMelody > 0 ? new Layer(Kind.MELODY, "melody", mel, w) : null;
        Layer bassLayer = s.volBass > 0 ? new Layer(Kind.BASS, "bass", bass, wBass) : null;
        Layer noiseLayer = s.volNoise > 0 ? new Layer(Kind.NOISE, "noise", noise, w) : null;
        // detail: what noise leaves above detail_from_hz (consonants), less strict, at most volume.detail loud
        float[] wHigh = new float[B];
        float[] overDetail = new float[B];
        boolean[] high = new boolean[B];
        boolean[] aboveBass = new boolean[B];
        for (int b = 0; b < B; b++) {
            high[b] = Spectra.frequency(b) >= s.detailFromHz;
            aboveBass[b] = Spectra.frequency(b) >= s.bassBelowHz;
            wHigh[b] = high[b] ? w[b] : 0;
            overDetail[b] = (float) (w[b] * (1 + (s.detailOvershoot - 1) / Math.sqrt(s.saturation)));
        }
        Layer detail = null;
        if (s.volDetail > 0) {
            detail = new Layer(Kind.DETAIL, "detail", new float[all.length], wHigh);
            detail.overW = overDetail;
            detail.gainCap = (float) s.volDetail;
            detail.similarity = Math.min(1, s.detailSimilarity / Math.sqrt(s.saturation));
            detail.screenOn = new float[all.length];
            for (int i = 0; i < all.length; i++) detail.screenOn[i] = high[i % B] ? all[i] : 0;
        }
        // what the melody can't play (a voice is rarely one clean tone) goes on to the noise layer, slice by slice;
        // what noise leaves in the highs goes on to detail
        if (melody != null) {
            melody.handTo = noiseLayer != null ? noiseLayer : detail;
            melody.handBins = aboveBass;
        }
        if (noiseLayer != null) {
            noiseLayer.handTo = detail;
            noiseLayer.handBins = high;
            if (melody != null) {
                noiseLayer.screenOn = new float[all.length];
                for (int i = 0; i < all.length; i++) noiseLayer.screenOn[i] = noise[i] + mel[i];
            }
        }
        for (Layer l : new Layer[] {melody, bassLayer, noiseLayer, detail}) if (l != null) layers.add(l);
        int slices = (frames + slice - 1) / slice;
        double inputTotal = 0; // the cost of the whole input, before anything plays
        for (Layer l : layers) {
            int loud = 0;
            float[] target = l.screenOn != null ? l.screenOn : l.r;
            for (int t = 0; t < frames; t++) {
                double e = 0;
                double own = 0;
                for (int b = 0; b < B; b++) {
                    e += l.cost(target[t * B + b], b);
                    own += l.cost(l.r[t * B + b], b);
                }
                l.total += e;
                if (l.kind != Kind.DETAIL) inputTotal += own;
                if (e > 1e-12) loud++;
            }
            l.threshold = Math.max(1e-12, minGain * (loud > 0 ? l.total / loud : 0) * slice);
            l.candAtom = new int[slices * candidates];
            l.candScore = new float[slices * candidates];
            Arrays.fill(l.candAtom, -1);
            // FORMAT: mosaic progress (slices)
            say.accept(l.name + ": comparing " + sounds.size() + " sounds x " + pitches + " pitches at " + slices
                    + " slices of " + slice * 10 + " ms");
            screen(l, slices);
        }
        say.accept("placing"); // FORMAT: mosaic progress (slices)

        int margin = 0;
        for (Solver.Sound snd : sounds) margin = Math.max(margin, framesAt(snd.entry().frames(), 0) + 2);
        int[] voices = new int[frames + margin + 1];
        int[] streams = new int[frames + margin + 1];
        int[] startsPerTick = new int[frames / Spectra.FRAMES_PER_TICK + 2];
        Map<Integer, List<Integer>> startsOf = new HashMap<>();
        List<Solver.Play> plays = new ArrayList<>();
        float[] tmp = new float[B];
        for (int sl = 0; sl < slices; sl++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            int from = sl * slice;
            int to = Math.min(frames, from + slice);
            for (Layer layer : layers) {
                // 1. sounds from before: go on for this slice (melody: while in key; else while it helps)
                for (int i = layer.active.size() - 1; i >= 0; i--) {
                    Active a = layer.active.get(i);
                    int n = horizon(a.sound, a.pitch);
                    int until = Math.min(n, a.length + (to - from));
                    Dictionary.Entry e = sounds.get(a.sound).entry();
                    double drop = 0;
                    double adds = 0;
                    for (int t = a.length; t < until && a.start + t < frames; t++) {
                        row(e, a.pitch, t, tmp);
                        int at = (a.start + t) * B;
                        for (int b = 0; b < B; b++) {
                            float v = layer.r[at + b];
                            float g = a.gain * tmp[b];
                            drop += layer.cost(v, b) - layer.cost(v - g, b);
                            adds += layer.overW[b] * g * g;
                        }
                    }
                    boolean goOn;
                    if (layer.kind == Kind.MELODY) {
                        // in key, and not drowning the rest: it may cost at most a quarter of what it adds
                        goOn = until > a.length && inKey(features[a.sound].note + shiftOf[a.pitch], sl)
                                && drop > -0.25 * adds;
                    } else {
                        goOn = until > a.length && drop > 0;
                    }
                    if (goOn) {
                        for (int t = a.length; t < until && a.start + t < frames; t++) {
                            row(e, a.pitch, t, tmp);
                            int at = (a.start + t) * B;
                            for (int b = 0; b < B; b++) layer.r[at + b] -= a.gain * tmp[b];
                        }
                        for (int t = a.start + a.length; t < a.start + until; t++) {
                            voices[t]++;
                            if (sounds.get(a.sound).stream()) streams[t]++;
                        }
                        a.length = until;
                    }
                    if (!goOn || until == n) {
                        boolean stopped = !(goOn && endsByItself(a.sound, a.pitch));
                        plays.add(new Solver.Play(a.sound, rateOf[a.pitch], a.start, a.length, stopped, a.gain));
                        layer.active.remove(i);
                    }
                }
                // 2. new sounds, the best first, while they help
                int tick = from / Spectra.FRAMES_PER_TICK;
                boolean[] tried = new boolean[candidates];
                while (startsPerTick[tick] < s.maxPerTick) {
                    double best = layer.threshold;
                    int bestC = -1;
                    float bestG = 0;
                    int bestLen = 0;
                    for (int c = 0; c < candidates; c++) {
                        int atom = layer.candAtom[sl * candidates + c];
                        if (atom < 0 || tried[c]) continue;
                        int sound = atom / pitches;
                        int pitch = atom % pitches;
                        // a single hit is judged on its whole length (it can't be stopped), others on this slice
                        int len = stoppable(sound) ? Math.min(to - from, horizon(sound, pitch)) : horizon(sound, pitch);
                        Dictionary.Entry e = sounds.get(sound).entry();
                        double cc = 0, aa = 0, rr = 0;
                        for (int t = 0; t < len && from + t < frames; t++) {
                            row(e, pitch, t, tmp);
                            int at = (from + t) * B;
                            for (int b = 0; b < B; b++) {
                                float a = tmp[b];
                                float v = layer.r[at + b];
                                cc += layer.wMiss[b] * v * a;
                                aa += layer.wMiss[b] * a * a;
                                if (v > 0) rr += layer.wMiss[b] * v * v;
                            }
                        }
                        if (cc <= 0 || aa <= 0 || rr <= 0 || cc / Math.sqrt(aa * rr) < layer.similarity) continue;
                        float g = (float) Math.min(layer.gainCap, cc / aa);
                        double drop = 0;
                        for (int t = 0; t < len; t++) {
                            row(e, pitch, t, tmp);
                            if (from + t >= frames) {
                                for (int b = 0; b < B; b++) drop -= layer.overW[b] * g * g * tmp[b] * tmp[b];
                                continue;
                            }
                            int at = (from + t) * B;
                            for (int b = 0; b < B; b++) {
                                float v = layer.r[at + b];
                                drop += layer.cost(v, b) - layer.cost(v - g * tmp[b], b);
                            }
                        }
                        if (drop > best) {
                            best = drop;
                            bestC = c;
                            bestG = g;
                            bestLen = len;
                        }
                    }
                    if (bestC < 0) break;
                    tried[bestC] = true;
                    int atom = layer.candAtom[sl * candidates + bestC];
                    int sound = atom / pitches;
                    int pitch = atom % pitches;
                    boolean stream = sounds.get(sound).stream();
                    boolean free = true;
                    for (int t = from; t < from + bestLen && t < voices.length; t++) {
                        if (voices[t] >= s.maxVoices || stream && streams[t] >= s.maxStreams) {
                            free = false;
                            break;
                        }
                    }
                    if (!free || !rateOk(startsOf, sound, from)) continue;
                    Dictionary.Entry e = sounds.get(sound).entry();
                    for (int t = 0; t < bestLen && from + t < frames; t++) {
                        row(e, pitch, t, tmp);
                        int at = (from + t) * B;
                        for (int b = 0; b < B; b++) layer.r[at + b] -= bestG * tmp[b];
                    }
                    for (int t = from; t < from + bestLen && t < voices.length; t++) {
                        voices[t]++;
                        if (stream) streams[t]++;
                    }
                    startsOf.computeIfAbsent(sound, x -> new ArrayList<>()).add(from);
                    startsPerTick[tick]++;
                    layer.placed++;
                    int n = horizon(sound, pitch);
                    if (bestLen >= n) {
                        plays.add(new Solver.Play(sound, rateOf[pitch], from, n, !endsByItself(sound, pitch), bestG));
                    } else {
                        layer.active.add(new Active(sound, pitch, from, bestG, bestLen));
                    }
                }
                // 3. what this layer couldn't explain in this slice goes on to the next layer
                if (layer.handTo != null) {
                    for (int t = from; t < to; t++) {
                        for (int b = 0; b < B; b++) {
                            int i = t * B + b;
                            if (layer.handBins[b] && layer.r[i] > 0) {
                                layer.handTo.r[i] += layer.r[i];
                                layer.r[i] = 0;
                            }
                        }
                    }
                }
            }
            // FORMAT: mosaic progress (slices)
            if (slices >= 10 && (sl + 1) % (slices / 10) == 0) say.accept("placing: " + (sl + 1) * 100 / slices + "%");
        }
        for (Layer l : layers) {
            for (Active a : l.active) plays.add(new Solver.Play(a.sound, rateOf[a.pitch], a.start, a.length, true, a.gain));
        }

        Shared.Keys keys = Shared.keys(plays, sounds);
        StringBuilder report = new StringBuilder();
        double total = inputTotal;
        double left = 0;
        for (Layer l : layers) {
            for (int t = 0; t < frames; t++) for (int b = 0; b < B; b++) left += l.cost(l.r[t * B + b], b);
            // FORMAT: mosaic slice layer report
            report.append(l.name).append(": ").append(l.placed).append(" placed\n");
        }
        int most = 0;
        for (int v : voices) most = Math.max(most, v);
        long cut = keys.plays().stream().filter(Solver.Play::stopped).count();
        // FORMAT: mosaic result summary (slices)
        report.append("slices of ").append(slice * 10).append(" ms: ").append(keys.plays().size()).append(" sounds (")
                .append(cut).append(" cut off), at most ").append(most).append(" at once, explains ")
                .append(Math.round(Math.max(0, 100 * (1 - left / Math.max(1e-12, total))))).append("% of the input")
                .append(keys.dropped() > 0 ? "; " + keys.dropped() + " sounds left out (no free stop key)" : "").append('\n');
        return new Solver.Result(keys.plays(), keys.use(), keys.source(), report.toString());
    }

    /**
     * Per frame and bin, the share of the input that is tonal (0..1): the median over 17 frames (170 ms, what holds
     * still in time: tones) against the median over 17 bins (8.5 semitones, what holds still across frequencies:
     * noise and hits), as a soft mask H² / (H² + P²).
     */
    private float[] harmonicShare(float[] spec) {
        float[] out = new float[spec.length];
        float[] window = new float[17];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                int n = 0;
                for (int d = -8; d <= 8; d++) if (t + d >= 0 && t + d < frames) window[n++] = spec[(t + d) * B + b];
                Arrays.sort(window, 0, n);
                float h = window[n / 2];
                n = 0;
                for (int d = -8; d <= 8; d++) if (b + d >= 0 && b + d < B) window[n++] = spec[t * B + b + d];
                Arrays.sort(window, 0, n);
                float p = window[n / 2];
                float hh = h * h;
                float pp = p * p;
                out[t * B + b] = hh + pp > 0 ? hh / (hh + pp) : 0;
            }
        }
        return out;
    }

    /** The rate limit: at most rate_limit.count starts of one file in any rate_limit.ticks long window. */
    private boolean rateOk(Map<Integer, List<Integer>> startsOf, int sound, int start) {
        List<Integer> mine = startsOf.get(sound);
        if (mine == null) return true;
        int window = s.rateTicks * Spectra.FRAMES_PER_TICK;
        int count = 1;
        for (int i = mine.size() - 1; i >= 0 && start - mine.get(i) < window; i--) count++;
        return count <= s.rateCount;
    }

    /** The best few sound starts per slice for one layer, by how well their first slice fits its part (all cores). */
    private void screen(Layer layer, int slices) throws Exception {
        float[] r = layer.screenOn != null ? layer.screenOn : layer.r;
        float[] rw = new float[r.length];
        for (int t = 0; t < frames; t++) {
            for (int b = 0; b < B; b++) {
                float v = r[t * B + b];
                rw[t * B + b] = v * (v > 0 ? layer.wMiss[b] : layer.overW[b]);
            }
        }
        int k = candidates;
        int chunks = Math.max(1, Math.min(slices, pool.getParallelism() * 4));
        pool.submit(() -> IntStream.range(0, chunks).parallel().forEach(chunk -> {
            int from = (int) ((long) slices * chunk / chunks);
            int to = (int) ((long) slices * (chunk + 1) / chunks);
            float[] head = new float[slice * B];
            float[] tmp = new float[B];
            for (int sound = 0; sound < sounds.size(); sound++) {
                if (layer.kind == Kind.BASS && features[sound].purity < s.bassPurity) continue;
                if (layer.kind == Kind.MELODY && features[sound].note == Features.NONE) continue;
                Dictionary.Entry entry = sounds.get(sound).entry();
                for (int p = 0; p < pitches; p++) {
                    if (!usable(sound, p)) continue;
                    if (layer.kind == Kind.MELODY && tonality(sound, p, slice) < s.melodyTonality) continue;
                    int len = Math.min(slice, horizon(sound, p));
                    double aw = 0;
                    for (int t = 0; t < len; t++) {
                        row(entry, p, t, tmp);
                        System.arraycopy(tmp, 0, head, t * B, B);
                        for (int b = 0; b < B; b++) aw += layer.wMiss[b] * tmp[b] * tmp[b];
                    }
                    if (aw <= 0) continue;
                    int atom = sound * pitches + p;
                    for (int sl = from; sl < to; sl++) {
                        if (layer.kind == Kind.MELODY && !layer.takes(sound, p, sl)) continue;
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
                        double g = Math.min(layer.gainCap, c / aw);
                        double score = 2 * g * c - g * g * aw;
                        int base = sl * k;
                        int weakest = base;
                        for (int i = base; i < base + k; i++) {
                            if (layer.candAtom[i] < 0) {
                                weakest = i;
                                break;
                            }
                            if (layer.candScore[i] < layer.candScore[weakest]) weakest = i;
                        }
                        if (layer.candAtom[weakest] < 0 || score > layer.candScore[weakest]) {
                            layer.candAtom[weakest] = atom;
                            layer.candScore[weakest] = (float) score;
                        }
                    }
                }
            }
        })).get();
    }
}
