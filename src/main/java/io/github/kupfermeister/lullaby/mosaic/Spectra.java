package io.github.kupfermeister.lullaby.mosaic;

/**
 * The spectrogram everything is compared in: audio at 24000 Hz, a 2048-sample Hann window every 240 samples
 * (10 ms, so a tick is 5 frames), magnitudes gathered into one bin per semitone from 55 Hz (A1) up to about
 * 10.5 kHz. One bin per semitone makes a pitch change a shift by whole bins, and resampling (what the game's pitch
 * does) also makes the sound shorter or longer, which the solver does on the frames. Magnitudes of sounds playing
 * together are treated as adding up (the usual assumption of this kind of matching). Any thread (plain data only).
 */
final class Spectra {

    private Spectra() {
    }

    static final int RATE = 24000;
    static final int HOP = 240;
    static final int FRAMES_PER_TICK = 5;
    static final int N = 2048;
    static final int BINS = 92;
    static final double LOWEST = 55.0;

    private static final float[] WINDOW = new float[N];
    /** For each semitone bin: the FFT bins that feed it and their weights (a triangle one semitone wide each side). */
    private static final int[][] FEED = new int[BINS][];
    private static final float[][] WEIGHT = new float[BINS][];

    static {
        for (int i = 0; i < N; i++) WINDOW[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / N));
        double binHz = (double) RATE / N;
        for (int b = 0; b < BINS; b++) {
            double center = frequency(b);
            java.util.List<Integer> feed = new java.util.ArrayList<>();
            java.util.List<Float> weight = new java.util.ArrayList<>();
            for (int j = 1; j < N / 2; j++) {
                double semis = 12 * Math.log(j * binHz / center) / Math.log(2);
                double w = 1 - Math.abs(semis);
                if (w > 0) {
                    feed.add(j);
                    weight.add((float) w);
                }
            }
            if (feed.isEmpty()) {
                // below about 200 Hz a semitone is narrower than one FFT bin: take the nearest bin
                feed.add((int) Math.max(1, Math.round(center / binHz)));
                weight.add(1f);
            }
            FEED[b] = feed.stream().mapToInt(Integer::intValue).toArray();
            WEIGHT[b] = new float[weight.size()];
            for (int i = 0; i < weight.size(); i++) WEIGHT[b][i] = weight.get(i);
        }
    }

    static double frequency(int bin) {
        return LOWEST * Math.pow(2, bin / 12.0);
    }

    /** Frames x BINS magnitudes (row-major) of 24 kHz samples; frame t is centered on sample t*HOP. */
    static float[] spectrogram(float[] samples, int[] framesOut) {
        int frames = samples.length / HOP + 1;
        framesOut[0] = frames;
        float[] out = new float[frames * BINS];
        double[] re = new double[N];
        double[] im = new double[N];
        double[] power = new double[N / 2];
        for (int t = 0; t < frames; t++) {
            int start = t * HOP - N / 2;
            for (int i = 0; i < N; i++) {
                int k = start + i;
                re[i] = k >= 0 && k < samples.length ? samples[k] * WINDOW[i] : 0;
                im[i] = 0;
            }
            fft(re, im);
            for (int j = 0; j < N / 2; j++) power[j] = re[j] * re[j] + im[j] * im[j];
            for (int b = 0; b < BINS; b++) {
                double p = 0;
                int[] feed = FEED[b];
                float[] w = WEIGHT[b];
                for (int i = 0; i < feed.length; i++) p += power[feed[i]] * w[i];
                out[t * BINS + b] = (float) Math.sqrt(p);
            }
        }
        return out;
    }

    /** In-place radix-2 FFT. */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wr = Math.cos(angle);
            double wi = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double cr = 1;
                double ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k;
                    int b = a + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double next = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = next;
                }
            }
        }
    }

    /**
     * How tonal each frame is (0 noise .. 1 pure tone): the share of the frame's energy in bins that stand out at
     * least twice above the median of the 9 bins around them (peaks: a tone or its harmonics). Noise has a smooth
     * spectrum, so few bins stand out. The same measure is used for sound files and for the input, so they match.
     */
    static float[] tonalityPerFrame(float[] spec, int frames) {
        float[] out = new float[frames];
        float[] around = new float[9];
        for (int t = 0; t < frames; t++) {
            double peak = 0;
            double all = 0;
            int row = t * BINS;
            for (int b = 0; b < BINS; b++) {
                int n = 0;
                for (int d = -4; d <= 4; d++) {
                    int k = b + d;
                    if (k >= 0 && k < BINS) around[n++] = spec[row + k];
                }
                java.util.Arrays.sort(around, 0, n);
                float median = around[n / 2];
                double e = (double) spec[row + b] * spec[row + b];
                all += e;
                if (spec[row + b] > 2 * median) peak += e;
            }
            out[t] = all > 0 ? (float) (peak / all) : 0;
        }
        return out;
    }

    /** One number for a whole sound: the frames' tonality weighted by their energy. */
    static float tonality(float[] spec, int frames) {
        float[] per = tonalityPerFrame(spec, frames);
        double sum = 0;
        double weight = 0;
        for (int t = 0; t < frames; t++) {
            double e = 0;
            for (int b = 0; b < BINS; b++) e += (double) spec[t * BINS + b] * spec[t * BINS + b];
            sum += per[t] * e;
            weight += e;
        }
        return weight > 0 ? (float) (sum / weight) : 0;
    }

    /** Frequency weights per bin: speech, music (flat) or custom points (Hz:weight, straight lines on a log scale). */
    static float[] weights(Settings s) {
        double[][] points = switch (s.weighting) {
            case "speech" -> new double[][] {{150, 0.7}, {300, 2}, {3400, 2}, {4500, 1.5}, {8000, 1.5}, {10000, 1}};
            case "music" -> new double[][] {{100, 1}};
            default -> s.customWeighting;
        };
        float[] w = new float[BINS];
        for (int b = 0; b < BINS; b++) {
            double f = frequency(b);
            double v;
            if (f <= points[0][0]) {
                v = points[0][1];
            } else if (f >= points[points.length - 1][0]) {
                v = points[points.length - 1][1];
            } else {
                int i = 0;
                while (points[i + 1][0] < f) i++;
                double x = Math.log(f / points[i][0]) / Math.log(points[i + 1][0] / points[i][0]);
                v = points[i][1] + x * (points[i + 1][1] - points[i][1]);
            }
            w[b] = (float) v;
        }
        return w;
    }

    // ---- storage: magnitudes as one byte on a dB scale (0.51 dB steps from -60 to +70 dB, 0 = silence) ----

    private static final float[] FROM_BYTE = new float[256];

    static {
        for (int q = 1; q < 256; q++) FROM_BYTE[q] = (float) Math.pow(10, (-60 + (q - 1) * 130.0 / 254) / 20);
    }

    static byte toByte(float m) {
        if (!(m > 0)) return 0;
        double db = 20 * Math.log10(m);
        int q = (int) Math.round((db + 60) * 254 / 130) + 1;
        if (q < 1) return 0;
        return (byte) Math.min(255, q);
    }

    static float fromByte(byte b) {
        return FROM_BYTE[b & 0xFF];
    }
}
