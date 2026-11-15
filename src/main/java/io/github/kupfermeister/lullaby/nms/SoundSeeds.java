package io.github.kupfermeister.lullaby.nms;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.RandomSource;

/**
 * sound_seed(): the seed that makes the game play one exact file of a sound.
 *
 * <p>How the game picks (read from the 26.3 client: ClientLevel.playSound, AbstractSoundInstance.resolve,
 * WeighedSoundEvents.getSound): the packet's seed becomes {@code RandomSource.create(seed)}; its first use is
 * {@code nextInt(total weight)} over the sound's list in sounds.json, walked top to bottom until the number runs out.
 * An entry of type "event" points at another sound; its weight is that sound's total weight (its own "weight" is
 * ignored) and the walk goes on inside it with the same random. This class builds the seed backwards from the file
 * (see seedFor) and checks it with the same steps and the same {@code RandomSource}.
 *
 * <p>Why not try seeds 0, 1, 2, ...: the game's random barely changes its first number between neighbouring seeds,
 * so with a total weight of 2, 4, 8, ... the same file comes back for hundreds of seeds, and a file with a 1-in-574
 * share took 0.4 s to find. Seeds stay below 2^48 (all the game's random uses), so a Lua number holds them exactly.
 *
 * <p>sounds.json is Mojang's, downloaded at build (build.gradle.kts, downloadSounds); the server jar has none.
 * Read on the first call, then kept, as are the seeds. Main thread only (called from Lua).
 */
public final class SoundSeeds {

    private SoundSeeds() {
    }

    private record Entry(String name, int weight, boolean event) {
    }

    private static final long M = 0x5DEECE66DL; // the game's (and java.util.Random's) multiplier
    private static final long M_INVERSE = 0xDFE05BCB1365L; // M * M_INVERSE = 1 in the lowest 48 bits
    private static final long MASK = (1L << 48) - 1; // the game's random keeps 48 bits

    private static Map<String, List<Entry>> pools; // sound name without "minecraft:" → its list
    private static final Map<String, Map<String, Long>> seeds = new HashMap<>(); // sound → file → seed

    public static long seed(String sound, String file) {
        String name = vanilla(sound, "sound");
        String wanted = vanilla(file, "file");
        Map<String, List<Entry>> all = pools();
        // FORMAT: sound_seed sound unknown
        if (!all.containsKey(name)) throw new IllegalArgumentException("sound_seed: sound must be a vanilla sound "
                + "from sounds.json, got \"" + sound + "\"");
        Map<String, Long> found = seeds.computeIfAbsent(name, SoundSeeds::search);
        Long seed = found.get(wanted);
        // FORMAT: sound_seed file not in the sound's list
        if (seed == null) throw new IllegalArgumentException("sound_seed: file must be one of the files of " + name
                + " (" + String.join(", ", files(name)) + "), got \"" + file + "\"");
        return seed;
    }

    /** Works out a seed for every file the sound can play. */
    private static Map<String, Long> search(String name) {
        Map<String, Long> found = new HashMap<>();
        for (String file : files(name)) {
            Long seed = seedFor(name, file);
            // FORMAT: Lullaby bug (a file the game can play got no seed)
            if (seed == null) throw new IllegalStateException("Lullaby bug: sound_seed found no seed for " + file
                    + " of " + name);
            found.put(file, seed);
        }
        return found;
    }

    /**
     * Builds the seed backwards instead of trying seeds. The game's random (LegacyRandomSource, the same steps as
     * java.util.Random): create(seed) stores {@code (seed ^ M) & 2^48-1}; each draw does {@code state = state * M + 11}
     * and its first number is the top 31 bits of the new state. nextInt(total) turns that number into the walk's
     * start: for a total of 2, 4, 8, ... it is {@code total * number >> 31}, else {@code number % total}. So for the
     * entry that starts at offset n, the number to get is known; the state is that number followed by 17 free low
     * bits, and the steps are undone (M is odd, so it has an inverse). The 17 free bits only matter when the entry
     * is another sound ("event"): then its own draw comes next, so they are tried until that draw lands too.
     * Every seed is checked once with the game's own RandomSource before it is used.
     */
    private static Long seedFor(String name, String file) {
        List<Entry> list = pools().get(name);
        int total = weight(name, 0);
        int offset = 0;
        for (Entry e : list) {
            int w = e.event ? weight(e.name, 1) : e.weight;
            boolean here = e.event ? w > 0 && files(e.name).contains(file) : e.name.equals(file) && w > 0;
            if (here) {
                long number = (total & (total - 1)) == 0
                        ? ((long) offset << 31) / total + ((1L << 31) / total - 1) / 2 // middle of the range
                        : offset;
                int freeBits = e.event ? 1 << 17 : 1;
                for (int low = 0; low < freeBits; low++) {
                    long state = (number << 17) | low; // the state after the first draw
                    long seed = (((state - 11) * M_INVERSE) & MASK) ^ M; // undo "* M + 11", then the "^ M"
                    if (file.equals(pick(name, RandomSource.create(seed), 0))) return seed;
                }
            }
            offset += w;
        }
        return null;
    }

    /** WeighedSoundEvents.getSound: one nextInt over the total weight, walked; "event" entries go on inside. */
    private static String pick(String name, RandomSource random, int depth) {
        List<Entry> list = pools().get(name);
        int total = weight(name, 0);
        if (list == null || list.isEmpty() || total == 0) return null;
        int n = random.nextInt(total);
        for (Entry e : list) {
            n -= e.event ? weight(e.name, depth + 1) : e.weight;
            if (n < 0) return e.event ? (depth > 16 ? null : pick(e.name, random, depth + 1)) : e.name;
        }
        return null;
    }

    /** A sound's total weight; an "event" entry counts the total of the sound it points at (0 if there is none). */
    private static int weight(String name, int depth) {
        List<Entry> list = pools().get(name);
        if (list == null || depth > 16) return 0; // vanilla has no loops; 16 keeps a broken file from hanging
        int total = 0;
        for (Entry e : list) total += e.event ? weight(e.name, depth + 1) : e.weight;
        return total;
    }

    /** Every file the sound can play (weight above 0), in sounds.json's order, following "event" entries. */
    private static Set<String> files(String name) {
        Set<String> out = new LinkedHashSet<>();
        collect(name, out, new HashSet<>());
        return out;
    }

    private static void collect(String name, Set<String> out, Set<String> seen) {
        if (!seen.add(name)) return;
        List<Entry> list = pools().get(name);
        if (list == null) return;
        for (Entry e : list) {
            if (e.event) collect(e.name, out, seen);
            else if (e.weight > 0) out.add(e.name);
        }
    }

    /** "minecraft:x" and "x" are the same; another namespace is a resource pack's, which the server can't see. */
    private static String vanilla(String id, String field) {
        if (id.startsWith("minecraft:")) return id.substring("minecraft:".length());
        // FORMAT: sound_seed id from another namespace
        if (id.indexOf(':') >= 0) throw new IllegalArgumentException("sound_seed: " + field + " must be a vanilla "
                + "(minecraft:) " + field + ", got \"" + id + "\"");
        return id;
    }

    private static Map<String, List<Entry>> pools() {
        if (pools != null) return pools;
        var stream = SoundSeeds.class.getClassLoader().getResourceAsStream("lullaby/sounds.json");
        // FORMAT: Lullaby bug (the build did not pack sounds.json)
        if (stream == null) throw new IllegalStateException("Lullaby bug: lullaby/sounds.json is missing from the jar");
        Map<String, List<Entry>> read = new HashMap<>();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            for (var sound : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                JsonElement sounds = sound.getValue().getAsJsonObject().get("sounds");
                List<Entry> list = new ArrayList<>();
                if (sounds != null) {
                    for (JsonElement s : sounds.getAsJsonArray()) {
                        if (s.isJsonPrimitive()) {
                            list.add(new Entry(strip(s.getAsString()), 1, false));
                        } else {
                            JsonObject o = s.getAsJsonObject();
                            boolean event = o.has("type") && o.get("type").getAsString().equals("event");
                            int weight = o.has("weight") ? o.get("weight").getAsInt() : 1;
                            list.add(new Entry(strip(o.get("name").getAsString()), weight, event));
                        }
                    }
                }
                read.put(strip(sound.getKey()), list);
            }
        } catch (Exception e) {
            // FORMAT: Lullaby bug (sounds.json broken)
            throw new IllegalStateException("Lullaby bug: lullaby/sounds.json can't be read (" + e.getMessage() + ")", e);
        }
        pools = read;
        return pools;
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
