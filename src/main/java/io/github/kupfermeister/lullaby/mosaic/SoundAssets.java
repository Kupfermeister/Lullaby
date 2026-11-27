package io.github.kupfermeister.lullaby.mosaic;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Vanilla's sound files. The server jar has none, so they come from Mojang's asset server
 * ({@code resources.download.minecraft.net}, the same place the game downloads them from), each checked against its
 * SHA-1 from the list the build made ({@code lullaby/sound_assets.txt} in the jar: path, hash, size). Kept in
 * {@code plugins/lullaby/sound_mosaicing/assets/<first 2 of hash>/<hash>}, only the files a job needs, once.
 * Runs on the mosaic job thread (8 downloads at a time).
 */
final class SoundAssets {

    record Asset(String file, String hash, long size) {
    }

    private static Map<String, Asset> list;

    static synchronized Map<String, Asset> list() throws IOException {
        if (list != null) return list;
        Map<String, Asset> out = new HashMap<>();
        InputStream in = SoundAssets.class.getClassLoader().getResourceAsStream("lullaby/sound_assets.txt");
        // FORMAT: Lullaby bug (the build did not pack the asset list)
        if (in == null) throw new IllegalStateException("Lullaby bug: lullaby/sound_assets.txt is missing from the jar");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.trim().split(" ");
                if (p.length == 3) out.put(p[0], new Asset(p[0], p[1], Long.parseLong(p[2])));
            }
        }
        list = out;
        return list;
    }

    private final Path folder;

    SoundAssets(Path folder) {
        this.folder = folder;
    }

    Path path(Asset a) {
        return folder.resolve(a.hash.substring(0, 2)).resolve(a.hash);
    }

    /** Downloads the files that aren't there yet; says progress through {@code say} every 10%. */
    void ensure(List<Asset> wanted, Consumer<String> say) throws IOException {
        List<Asset> missing = new ArrayList<>();
        long bytes = 0;
        for (Asset a : wanted) {
            if (!Files.exists(path(a))) {
                missing.add(a);
                bytes += a.size;
            }
        }
        if (missing.isEmpty()) return;
        // FORMAT: mosaic download start
        say.accept("downloading " + missing.size() + " sound files from Mojang (" + (bytes / 100000) / 10.0 + " MB), once");
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "Lullaby mosaic download");
            t.setDaemon(true);
            return t;
        });
        AtomicInteger done = new AtomicInteger();
        int total = missing.size();
        try {
            List<Future<?>> jobs = new ArrayList<>();
            for (Asset a : missing) {
                jobs.add(pool.submit(() -> {
                    download(http, a);
                    int n = done.incrementAndGet();
                    // FORMAT: mosaic download progress
                    if (n * 10 / total != (n - 1) * 10 / total) say.accept("downloaded " + n + " / " + total);
                    return null;
                }));
            }
            for (Future<?> f : jobs) {
                try {
                    f.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable c = e.getCause();
                    throw c instanceof IOException io ? io : new IOException(c.getMessage(), c);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("stopped", e); // FORMAT: mosaic job stopped
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void download(HttpClient http, Asset a) throws Exception {
        URI uri = URI.create("https://resources.download.minecraft.net/" + a.hash.substring(0, 2) + "/" + a.hash);
        byte[] body = null;
        for (int attempt = 0; attempt < 3 && body == null; attempt++) {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() == 200) body = r.body();
        }
        // FORMAT: mosaic download failed
        if (body == null) throw new IOException("couldn't download " + a.file + " from Mojang");
        String sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(body));
        // FORMAT: mosaic download wrong hash
        if (!sha1.equals(a.hash)) throw new IOException(a.file + " came with the wrong SHA-1 (" + sha1 + ")");
        Path target = path(a);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(a.hash + ".part");
        Files.write(temp, body);
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
