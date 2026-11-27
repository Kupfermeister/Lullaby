package io.github.kupfermeister.lullaby.mosaic;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The mosaic mixed into a .wav (48 kHz mono) the way the game plays it: each file resampled by its pitch (the game's
 * pitch is resampling too), times its gain, cut off where it is stopped (2 ms fade instead of a click), all added
 * up. Lets the mosaic be heard and tuned without the game. Runs on the mosaic job thread.
 */
final class Preview {

    private static final int RATE = 48000;

    private Preview() {
    }

    static void write(Path out, Solver.Result result, List<Solver.Sound> sounds, SoundAssets assets,
                      Map<String, SoundAssets.Asset> list) throws IOException {
        int end = 0;
        for (Solver.Play p : result.plays()) end = Math.max(end, p.start() + p.length());
        float[] mix = new float[end * RATE / 100 + RATE];
        Map<String, AudioFiles.Pcm> decoded = new HashMap<>();
        for (Solver.Play p : result.plays()) {
            String file = sounds.get(p.sound()).entry().file();
            AudioFiles.Pcm pcm = decoded.get(file);
            if (pcm == null) {
                try (var in = new BufferedInputStream(Files.newInputStream(assets.path(list.get(file))), 1 << 16)) {
                    pcm = AudioFiles.ogg(in, 0);
                }
                decoded.put(file, pcm);
            }
            double step = p.pitch() * pcm.rate() / RATE;
            int length = Math.min(p.length() * RATE / 100, (int) (pcm.samples().length / step));
            float[] part = AudioFiles.resample(pcm.samples(), step, length);
            int at = p.start() * RATE / 100;
            int fade = p.stopped() ? RATE / 500 : 0;
            for (int i = 0; i < length && at + i < mix.length; i++) {
                float g = p.gain();
                if (fade > 0 && i >= length - fade) g *= (float) (length - i) / fade;
                mix[at + i] += part[i] * g;
            }
        }
        float peak = 0;
        for (float v : mix) peak = Math.max(peak, Math.abs(v));
        if (peak > 0.99f) for (int i = 0; i < mix.length; i++) mix[i] *= 0.99f / peak;
        AudioFiles.writeWav(out, mix, RATE);
    }
}
