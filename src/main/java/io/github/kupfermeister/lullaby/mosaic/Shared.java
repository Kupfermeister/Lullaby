package io.github.kupfermeister.lullaby.mosaic;

import io.github.kupfermeister.lullaby.nms.SoundSeeds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What both mosaic methods (Solver: layers, Slicer: slices) share: scaling the input to the sounds' loudness and
 * the stop keys. Plain data only, any thread.
 */
final class Shared {

    private Shared() {
    }

    private static final int B = Spectra.BINS;

    /**
     * Scales the input spectrogram in place so its loud parts (95th percentile of the frames, weighted) equal
     * {@code loudness} times one typical sound at full volume (the median of the sounds' loudest frames). Returns
     * the factor.
     */
    static double scale(float[] r, int frames, List<Solver.Sound> sounds, float[] w, double loudness) {
        double[] peaks = new double[sounds.size()];
        for (int i = 0; i < sounds.size(); i++) {
            Dictionary.Entry e = sounds.get(i).entry();
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
        Arrays.sort(norms);
        double p95 = norms.length == 0 ? 0 : norms[(int) (norms.length * 0.95)];
        double scale = p95 > 0 ? loudness * reference / p95 : 1;
        for (int i = 0; i < r.length; i++) r[i] *= (float) scale;
        return scale;
    }

    record Keys(List<Solver.Play> plays, List<SoundSeeds.Use> use, List<String> source, int dropped) {
    }

    /**
     * stopsound stops every playing sound of one sound id on one source (volume slider), so every play gets a sound
     * id (any whose list has the file) and a source such that no stop hits another play. A cut-off play that gets no
     * free key is left out (counted in dropped).
     */
    static Keys keys(List<Solver.Play> plays, List<Solver.Sound> sounds) {
        List<String> sources = new ArrayList<>(SoundSeeds.sources());
        sources.remove("master");
        sources.addFirst("master");
        List<Solver.Play> sorted = new ArrayList<>(plays);
        sorted.sort((a, b) -> Integer.compare(a.start(), b.start()));
        Map<String, List<Solver.Play>> onKey = new HashMap<>();
        List<Solver.Play> out = new ArrayList<>();
        List<SoundSeeds.Use> use = new ArrayList<>();
        List<String> source = new ArrayList<>();
        int dropped = 0;
        for (Solver.Play p : sorted) {
            boolean found = false;
            search:
            for (SoundSeeds.Use u : sounds.get(p.sound()).uses()) {
                for (String src : sources) {
                    String key = u.sound() + "|" + src;
                    List<Solver.Play> others = onKey.computeIfAbsent(key, x -> new ArrayList<>());
                    boolean clash = false;
                    for (int i = others.size() - 1; i >= 0; i--) {
                        Solver.Play o = others.get(i);
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

    /**
     * Does a's stop hit b while b plays? Stops are sent before starts of the same moment; 2 frames of slack for a
     * sound that ends by itself. A b that is stopped at the very same moment is not hurt (it stops anyway).
     */
    private static boolean clashes(Solver.Play a, Solver.Play b) {
        if (!a.stopped() || a == b) return false;
        int stop = a.start() + a.length();
        if (b.stopped() && b.start() + b.length() == stop) return false;
        return b.start() < stop && stop < b.start() + b.length() + (b.stopped() ? 0 : 2);
    }
}
