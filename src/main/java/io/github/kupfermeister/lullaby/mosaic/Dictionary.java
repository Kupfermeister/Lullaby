package io.github.kupfermeister.lullaby.mosaic;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The analysed sound files: per file its spectrogram (Spectra, one byte per value), how many frames it really has,
 * and its tonality. Built once per file and kept in {@code plugins/lullaby/sound_mosaicing/cache/dictionary.bin};
 * a file whose hash changed (a Minecraft update) or that was cut shorter than {@code max_sound_seconds} now asks is
 * built again. {@code tonality.txt} next to settings.txt lists every analysed file by tonality, for review.
 * Runs on the mosaic job thread and its pool.
 */
final class Dictionary {

    private static final int FORMAT = 1;

    /** One analysed file. frames: stored frames; realFrames: its whole length in frames, -1 if longer than stored. */
    record Entry(String file, String hash, double seconds, int frames, int realFrames, float tonality, byte[] spec) {
        float at(int t, int b) {
            return Spectra.fromByte(spec[t * Spectra.BINS + b]);
        }
    }

    private final Path file;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    Dictionary(Path file) {
        this.file = file;
    }

    Entry get(String name) {
        return entries.get(name);
    }

    void load() throws IOException {
        if (!Files.exists(file)) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 20))) {
            if (in.readInt() != FORMAT) return;
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                String hash = in.readUTF();
                double seconds = in.readDouble();
                int frames = in.readInt();
                int real = in.readInt();
                float tonality = in.readFloat();
                byte[] spec = new byte[frames * Spectra.BINS];
                in.readFully(spec);
                entries.put(name, new Entry(name, hash, seconds, frames, real, tonality, spec));
            }
        } catch (IOException e) {
            entries.clear(); // a broken cache is built again
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".part");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp), 1 << 20))) {
            out.writeInt(FORMAT);
            List<Entry> all = new ArrayList<>(entries.values());
            out.writeInt(all.size());
            for (Entry e : all) {
                out.writeUTF(e.file);
                out.writeUTF(e.hash);
                out.writeDouble(e.seconds);
                out.writeInt(e.frames);
                out.writeInt(e.realFrames);
                out.writeFloat(e.tonality);
                out.write(e.spec);
            }
        }
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Analyses the files not in the cache yet (in parallel) and saves the cache if anything was added. */
    void ensure(List<SoundAssets.Asset> wanted, SoundAssets assets, double maxSeconds, ForkJoinPool pool,
                Consumer<String> say) throws IOException {
        List<SoundAssets.Asset> todo = new ArrayList<>();
        for (SoundAssets.Asset a : wanted) {
            Entry e = entries.get(a.file());
            boolean fine = e != null && e.hash.equals(a.hash())
                    && (e.realFrames >= 0 ? true : e.seconds >= maxSeconds - 1e-9);
            if (!fine) todo.add(a);
        }
        if (todo.isEmpty()) return;
        // FORMAT: mosaic analysis start
        say.accept("analysing " + todo.size() + " sound files, once (this can take a few minutes)");
        AtomicInteger done = new AtomicInteger();
        List<String> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            pool.submit(() -> todo.parallelStream().forEach(a -> {
                try (var in = new BufferedInputStream(Files.newInputStream(assets.path(a)), 1 << 16)) {
                    AudioFiles.Pcm pcm = AudioFiles.ogg(in, maxSeconds);
                    float[] at24 = AudioFiles.resample(pcm, Spectra.RATE);
                    int[] frames = new int[1];
                    float[] spec = Spectra.spectrogram(at24, frames);
                    byte[] bytes = new byte[spec.length];
                    for (int i = 0; i < spec.length; i++) bytes[i] = Spectra.toByte(spec[i]);
                    float tonality = Spectra.tonality(spec, frames[0]);
                    entries.put(a.file(), new Entry(a.file(), a.hash(), maxSeconds, frames[0],
                            pcm.cut() ? -1 : frames[0], tonality, bytes));
                } catch (Exception e) {
                    failed.add(a.file() + " (" + e.getMessage() + ")");
                }
                int n = done.incrementAndGet();
                // FORMAT: mosaic analysis progress
                if (n * 10 / todo.size() != (n - 1) * 10 / todo.size()) say.accept("analysed " + n + " / " + todo.size());
            })).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("stopped", e); // FORMAT: mosaic job stopped
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause().getMessage(), e.getCause());
        }
        // FORMAT: mosaic files that couldn't be analysed
        if (!failed.isEmpty()) say.accept(failed.size() + " sound files couldn't be read and are skipped: "
                + String.join(", ", failed.subList(0, Math.min(5, failed.size()))) + (failed.size() > 5 ? ", ..." : ""));
        save();
    }

    /** tonality.txt: every analysed file, most tonal first, with the value the solver uses (overrides applied). */
    void writeTonality(Path out, Map<String, Double> overrides) throws IOException {
        List<Entry> all = new ArrayList<>(entries.values());
        all.sort((a, b) -> Float.compare(tonality(b, overrides), tonality(a, overrides)));
        StringBuilder s = new StringBuilder();
        // FORMAT: tonality.txt header
        s.append("# Tonality of every analysed sound file (0 noise .. 1 pure tone), worked out from its spectrum:\n")
                .append("# the share of its energy in peaks (tones and their harmonics). Most tonal first.\n")
                .append("# Change one in settings.txt: tonality.<file> = <value>  (marked * here when changed)\n");
        for (Entry e : all) {
            float t = tonality(e, overrides);
            s.append(String.format(java.util.Locale.ROOT, "%.3f", t)).append(overrides.containsKey(e.file) ? "* " : "  ")
                    .append(e.file).append('\n');
        }
        Files.writeString(out, s.toString());
    }

    static float tonality(Entry e, Map<String, Double> overrides) {
        Double o = overrides.get(e.file);
        return o != null ? o.floatValue() : e.tonality;
    }
}
