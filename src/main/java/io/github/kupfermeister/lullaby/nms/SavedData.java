package io.github.kupfermeister.lullaby.nms;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.datafixers.DSL;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import io.github.kupfermeister.lullaby.lua.Wire;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CollectionTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.Util;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * server.data on disk: plugins/lullaby/data/server.snbt, vanilla's readable SNBT (what /data get prints), written and
 * read with vanilla's own printer, parser, codecs and data fixer. Lives as long as the plugin (survives reloads).
 *
 * <p>The file: {@code {DataVersion: <vanilla's data version>, data: {...}}}. Lua values become NBT so that they come
 * back exactly: text StringTag, true/false ByteTag 1b/0b (every other number never becomes a byte), whole numbers
 * IntTag (LongTag when too big), other numbers DoubleTag, lists ListTag, tables with text keys CompoundTag. A Lullaby
 * value is a compound with ONE key {@code "lullaby:<type>"}: players by UUID, worlds by id, small values (vec3, ...) by
 * their fields (data.lua makes them again), item_stack, component and block_state through vanilla's codec (and the
 * data fixer on load, when the file is from an older Minecraft).
 *
 * <p>Rules: a hand edit wins (the file changed since Lullaby last read or wrote it: Lullaby doesn't save over it, the
 * next load reads it); a file that didn't load cleanly is never saved over (that would lose what couldn't be read);
 * data past the size limit is not saved (the last file stays).
 *
 * <p>Threads: everything here runs on the main thread, except the file write after an autosave, which runs on this
 * class's one worker thread ("Lullaby data writer") on bytes it owns. Every other step first waits for that write.
 */
public final class SavedData {

    /** Shows an error or warning (kind "error" or "warning"); main thread. */
    public interface Reporter {
        void report(String kind, String via, String message);
    }

    // FORMAT: the file's name in errors
    public static final String FILE_TEXT = "plugins/lullaby/data/server.snbt";
    private static final String PREFIX = "lullaby:";
    private static final String DATA = "data";

    private record Stamp(FileTime modified, long size) {
    }

    /** Where a value is, for error texts: built into text only when there is a problem (no text per entry). */
    private record Place(Place parent, Object key) {
        Place child(Object key) {
            return new Place(this, key);
        }

        String text() {
            if (parent == null) return "server.data";
            String up = parent.text();
            if (key instanceof Integer i) return up + "[" + i + "]";
            String k = (String) key;
            if (k.matches("[A-Za-z_][A-Za-z0-9_]*")) return up + "." + k;
            return up + "[\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\"]";
        }
    }

    /** A value that can't be loaded or saved: already counted in the problem list, left out by the caller. */
    private static final class Skip extends Exception {
        Skip() {
            super(null, null, false, false);
        }
    }

    private final Path folder;
    private final Path file;
    private final Path old;
    private final Reporter reporter;
    private final Consumer<Runnable> mainThread;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Lullaby data writer");
        thread.setDaemon(true);
        return thread;
    });
    private Future<?> pending;
    private volatile Stamp known; // the file as Lullaby last read or wrote it (null: there was none)
    private boolean blocked; // the last load had problems: saving would lose what couldn't be read
    private long limitBytes = 16L << 20; // 0: no limit

    public SavedData(Path dataFolder, Reporter reporter, Consumer<Runnable> mainThread) {
        this.folder = dataFolder.resolve("data");
        this.file = folder.resolve("server.snbt");
        this.old = folder.resolve("server.snbt_old");
        this.reporter = reporter;
        this.mainThread = mainThread;
    }

    /** config data_max_size in megabytes; 0: no limit. */
    public void setLimit(double megabytes) {
        limitBytes = megabytes <= 0 ? 0 : (long) Math.min(Long.MAX_VALUE, megabytes * 1024 * 1024);
    }

    private static int currentVersion() {
        return SharedConstants.getCurrentVersion().dataVersion().version();
    }

    private void waitForWrite() {
        Future<?> write = pending;
        if (write == null) return;
        pending = null;
        try {
            write.get();
        } catch (Exception e) {
            // the write reported its own error
        }
    }

    private Stamp stamp() {
        try {
            BasicFileAttributes a = Files.readAttributes(file, BasicFileAttributes.class);
            return new Stamp(a.lastModifiedTime(), a.size());
        } catch (IOException e) {
            return null; // no file
        }
    }

    /**
     * Why server.data must not be saved now, or null. {@code reloading}: a reload follows (then a hand edit is about
     * to be loaded).
     */
    public String refusal(boolean reloading) {
        waitForWrite();
        if (blocked) {
            // FORMAT: not saving because the last load had problems
            return "server.data is not saved: " + FILE_TEXT + " did not load cleanly (see the errors from loading), and"
                    + " saving would lose what couldn't be read. Fix the file and /lullaby reload";
        }
        if (!Objects.equals(stamp(), known)) {
            // FORMAT: the file was changed by hand
            return FILE_TEXT + " was changed by hand since Lullaby last saved it, so it is not saved over. "
                    + (reloading ? "Loading it now: changes scripts made to server.data since the last save are dropped"
                    : "The next /lullaby reload or restart loads it (changes scripts make to server.data until then are"
                    + " dropped)");
        }
        return null;
    }

    // ---- saving: Java's picture of the save tree (data.lua I.data_save) -> NBT -> file ----

    /** What data.lua left out of the save tree (one text per line, "" if nothing), shown as red errors. */
    public void saveProblems(String lines) {
        if (!lines.isEmpty()) report("error", "saving server.data", List.of(lines.split("\n")));
    }

    /**
     * Writes the save tree. {@code wait}: write now on this thread (reload, stop) instead of on the worker (autosave).
     * Call {@link #refusal} first.
     */
    public void save(Object tree, IntFunction<Object> slots, boolean wait) {
        List<String> problems = new ArrayList<>();
        Tag data;
        try {
            data = toTag(tree, new Place(null, null), slots, problems);
        } catch (Skip e) {
            data = new CompoundTag();
        }
        report("error", "saving server.data", problems);
        if (!(data instanceof CompoundTag compound)) throw new IllegalStateException("Lullaby bug: server.data is not a table");
        if (compound.isEmpty() && known == null) return; // nothing saved yet and nothing to save: no file
        CompoundTag root = new CompoundTag();
        root.putInt(SharedConstants.DATA_VERSION_TAG, currentVersion());
        root.put(DATA, compound);
        long limit = limitBytes;
        if (wait) {
            write(root, limit, true);
        } else {
            pending = writer.submit(() -> write(root, limit, false)); // root is never touched again on the main thread
        }
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / 1048576.0);
    }

    /**
     * Prints the tag as SNBT, reads the text back with vanilla's parser (a file that can't be read back is never
     * written: it would load as nothing), then writes it as vanilla saves level.dat: a new file next to it first, then
     * swapped in; the one before stays as _old. {@code onMain}: errors are shown at once, else handed to the main thread.
     */
    private void write(CompoundTag root, long limit, boolean onMain) {
        try {
            String text = new SnbtPrinterTagVisitor().visit(root) + "\n";
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            if (limit > 0 && bytes.length > limit) {
                // FORMAT: server.data too big
                say(onMain, "server.data is " + megabytes(bytes.length) + " MB, more than data_max_size ("
                        + megabytes(limit) + " MB in config.lua): not saved, the last saved file stays");
                return;
            }
            String back;
            try {
                back = TagParser.parseCompoundFully(text).equals(root) ? null : "it reads back different";
            } catch (CommandSyntaxException e) {
                back = e.getMessage();
            }
            if (back != null) {
                // FORMAT: vanilla's printer wrote something its parser can't read (a Lullaby bug to fix)
                say(onMain, "Lullaby bug: the saved text of server.data can't be read back (" + back
                        + "): not saved, the last saved file stays");
                return;
            }
            Files.createDirectories(folder);
            Path fresh = Files.createTempFile(folder, "server", ".snbt");
            Files.write(fresh, bytes);
            Util.safeReplaceFile(file, fresh, old);
            known = stamp();
        } catch (IOException | RuntimeException e) {
            // FORMAT: the file couldn't be written
            say(onMain, "server.data could not be written to " + FILE_TEXT + ": "
                    + (e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private void say(boolean onMain, String message) {
        if (onMain) reporter.report("error", "saving server.data", message);
        else mainThread.accept(() -> reporter.report("error", "saving server.data", message));
    }

    private static Tag toTag(Object value, Place at, IntFunction<Object> slots, List<String> problems) throws Skip {
        switch (value) {
            case Boolean b -> {
                return ByteTag.valueOf(b);
            }
            case Double d -> {
                if (d == Math.floor(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) return IntTag.valueOf(d.intValue());
                if (d == Math.floor(d) && Math.abs(d) <= 0x1p53) return LongTag.valueOf(d.longValue());
                return DoubleTag.valueOf(d);
            }
            case Long l -> {
                return LongTag.valueOf(l);
            }
            case String s -> {
                return StringTag.valueOf(s);
            }
            case Wire.Table t when t.kind >= 0 -> {
                return codecValue(t, at, slots, problems);
            }
            case Wire.Table t -> {
                List<Object> list = t.hash.isEmpty() ? (t.array.contains(null) ? null : t.array) : Codecs.sequence(t);
                if (list != null && !list.isEmpty()) {
                    ListTag tags = new ListTag();
                    for (int i = 0; i < list.size(); i++) tags.add(toTag(list.get(i), at.child(i + 1), slots, problems));
                    return tags;
                }
                CompoundTag compound = new CompoundTag();
                for (Map.Entry<Object, Object> e : t.hash.entrySet()) {
                    if (!(e.getKey() instanceof String key)) throw new IllegalStateException("Lullaby bug: a key that is not text");
                    try {
                        compound.put(key, toTag(e.getValue(), at.child(key), slots, problems));
                    } catch (Skip skip) {
                        // left out; the problem is in the list
                    }
                }
                return compound;
            }
            case null, default -> throw new IllegalStateException("Lullaby bug: can't save " + value);
        }
    }

    private static Tag codecValue(Wire.Table t, Place at, IntFunction<Object> slots, List<String> problems) throws Skip {
        String kind = Codecs.kindName(t.kind);
        Tag inner;
        try {
            inner = switch (kind) {
                case "item_stack" -> get(ItemStack.CODEC.encodeStart(Codecs.nbt(),
                        (ItemStack) slots.apply(((Number) t.hash.get(Boolean.TRUE)).intValue())));
                case "component" -> get(ComponentSerialization.CODEC.encodeStart(Codecs.nbt(),
                        Codecs.componentFromTable(t, slots)));
                case "block_state" -> get(BlockState.CODEC.encodeStart(Codecs.nbt(),
                        Nms.state(((Number) t.array.get(0)).intValue())));
                default -> throw new IllegalStateException("Lullaby bug: data.lua sent a " + kind + " to save");
            };
        } catch (IllegalArgumentException e) {
            // FORMAT: a codec value vanilla can't save
            problems.add(at.text() + " can't be saved: " + Codecs.article(kind) + " vanilla can't write (" + e.getMessage() + ")");
            throw new Skip();
        }
        CompoundTag wrapped = new CompoundTag();
        wrapped.put(PREFIX + kind, inner);
        return wrapped;
    }

    private static <T> T get(DataResult<T> result) {
        return result.getOrThrow(IllegalArgumentException::new);
    }

    // ---- loading: file -> NBT -> Java's picture for the wire (data.lua I.data_load makes the Lua values) ----

    /** The saved tree as wire bytes, or null when there is nothing (no file, or it can't be read: reported). */
    public ByteBuffer read(ToIntFunction<Object> addSlot) {
        waitForWrite();
        blocked = false;
        known = stamp();
        if (known == null) return null;
        CompoundTag root;
        try {
            root = TagParser.parseCompoundFully(Files.readString(file, StandardCharsets.UTF_8));
        } catch (CommandSyntaxException | IOException | RuntimeException e) {
            // FORMAT: the file can't be read at all
            return unreadable("it can't be read (" + e.getMessage() + ")");
        }
        int current = currentVersion();
        int version = root.getIntOr(SharedConstants.DATA_VERSION_TAG, current);
        if (version > current) {
            // FORMAT: the file is from a newer Minecraft
            return unreadable("it was saved by a newer Minecraft (data version " + version + ", this server has " + current + ")");
        }
        Tag data = root.get(DATA);
        if (data == null) data = new CompoundTag();
        if (!(data instanceof CompoundTag)) return unreadable("its data must be a compound { }"); // FORMAT
        List<String> problems = new ArrayList<>();
        ByteBuffer bytes;
        try {
            Object tree;
            try {
                tree = fromTag(data, new Place(null, null), version, addSlot, problems);
            } catch (Skip e) {
                tree = Wire.EMPTY;
            }
            bytes = Wire.write(tree);
        } catch (RuntimeException e) {
            // too deep for Lua, a data fixer failure, ...: the whole file counts as unreadable, so it isn't saved over
            return unreadable("it can't be read (" + e.getMessage() + ")");
        }
        if (!problems.isEmpty()) {
            blocked = true;
            report("error", "loading server.data", problems);
        }
        return bytes;
    }

    /** data.lua failed as a whole while loading (a Lullaby bug): nothing is saved over the file. */
    public void loadFailed() {
        blocked = true;
    }

    private ByteBuffer unreadable(String why) {
        blocked = true;
        // FORMAT: the whole file can't be loaded
        reporter.report("error", "loading server.data", FILE_TEXT + " can't be loaded: " + why
                + ". server.data starts empty and is not saved until the file is fixed (then /lullaby reload)");
        return null;
    }

    /** What data.lua couldn't make again (one text per line, "" if none): then the file isn't saved over either. */
    public void loaded(String problems) {
        if (problems.isEmpty()) return;
        blocked = true;
        report("error", "loading server.data", List.of(problems.split("\n")));
    }

    private void report(String kind, String via, List<String> problems) {
        for (int i = 0; i < problems.size(); i++) {
            if (i == 10) {
                reporter.report(kind, via, "... and " + (problems.size() - 10) + " more"); // FORMAT
                break;
            }
            reporter.report(kind, via, problems.get(i));
        }
    }

    private Object fromTag(Tag tag, Place at, int version, ToIntFunction<Object> addSlot, List<String> problems)
            throws Skip {
        switch (tag) {
            case CompoundTag c -> {
                if (c.size() == 1) {
                    String only = c.keySet().iterator().next();
                    if (only.startsWith(PREFIX)) return typed(only.substring(PREFIX.length()), c.get(only), at, version, addSlot, problems);
                }
                Map<Object, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, Tag> e : c.entrySet()) {
                    String key = e.getKey();
                    if (key.startsWith(PREFIX)) {
                        // FORMAT: a key Lullaby keeps for itself, in the file
                        problems.add(at.child(key).text() + " can't be loaded: names starting with \"" + PREFIX
                                + "\" are kept for Lullaby itself");
                        continue;
                    }
                    try {
                        map.put(key, fromTag(e.getValue(), at.child(key), version, addSlot, problems));
                    } catch (Skip skip) {
                        // left out; the problem is in the list
                    }
                }
                return map.isEmpty() ? Wire.EMPTY : map;
            }
            case CollectionTag list -> {
                if (list.size() == 0) return Wire.EMPTY;
                List<Object> items = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    try {
                        items.add(fromTag(list.get(i), at.child(i + 1), version, addSlot, problems));
                    } catch (Skip skip) {
                        // A list keeps its order or goes whole (as data.lua does); said once.
                        String whole = " (so the whole list is left out)"; // FORMAT
                        if (!problems.getLast().endsWith(whole)) problems.set(problems.size() - 1, problems.getLast() + whole);
                        throw skip;
                    }
                }
                return items;
            }
            case StringTag s -> {
                return s.value();
            }
            case ByteTag b when b.value() == 0 || b.value() == 1 -> {
                return b.value() == 1;
            }
            case FloatTag f -> {
                return Double.parseDouble(Float.toString(f.value())); // the shortest number that is this float
            }
            case LongTag l -> {
                return l.value();
            }
            case NumericTag n -> {
                return n.doubleValue();
            }
            default -> {
                // FORMAT: an NBT kind Lua has no value for
                problems.add(at.text() + " can't be loaded: unknown kind of value " + tag);
                throw new Skip();
            }
        }
    }

    /** One {"lullaby:<type>": value}. Codec types and players are checked here; small values in data.lua. */
    private Object typed(String kind, Tag value, Place at, int version, ToIntFunction<Object> addSlot,
                         List<String> problems) throws Skip {
        Object result;
        try {
            result = switch (kind) {
                case "item_stack" -> {
                    ItemStack stack = get(ItemStack.CODEC.parse(Codecs.nbt(), fix(References.ITEM_STACK, value, version)));
                    // FORMAT: an empty item in the file
                    if (stack.isEmpty()) throw new IllegalArgumentException("the item is empty");
                    yield (double) addSlot.applyAsInt(stack);
                }
                case "component" -> Codecs.componentTree(get(ComponentSerialization.CODEC.parse(Codecs.nbt(),
                        fix(References.TEXT_COMPONENT, value, version))), addSlot, new int[1]);
                case "block_state" -> (double) Block.getId(get(BlockState.CODEC.parse(Codecs.nbt(),
                        fix(References.BLOCK_STATE, value, version))));
                case "player" -> {
                    // FORMAT: a saved player without a UUID
                    if (!(value instanceof StringTag s)) throw new IllegalArgumentException("a player needs its UUID as text");
                    yield UUID.fromString(s.value()).toString();
                }
                default -> fromTag(value, at, version, addSlot, problems); // data.lua checks the small values
            };
        } catch (IllegalArgumentException e) {
            // FORMAT: a Lullaby value in the file that can't be made
            problems.add(at.text() + " can't be loaded: " + Codecs.article(kind) + " that vanilla can't read ("
                    + e.getMessage() + ")");
            throw new Skip();
        }
        Map<Object, Object> wrapped = new LinkedHashMap<>();
        wrapped.put(PREFIX + kind, result);
        return wrapped;
    }

    /** A value saved by an older Minecraft, brought up to this one by vanilla's data fixer (as worlds are). */
    private static Tag fix(DSL.TypeReference type, Tag value, int version) {
        int current = currentVersion();
        if (version >= current) return value;
        return DataFixers.getDataFixer().update(type, new Dynamic<>(NbtOps.INSTANCE, value), version, current).getValue();
    }

    // ---- /lullaby data server ----

    /**
     * The view of one place (data.lua I.data_view: {tree} in save-tree shape) as vanilla's colored NBT text, the way
     * /data get shows it. Values vanilla can't write are left out of the view.
     */
    public static net.kyori.adventure.text.Component view(Object wrapped, IntFunction<Object> slots) {
        Object tree = ((Wire.Table) wrapped).array.getFirst();
        List<String> ignored = new ArrayList<>();
        Tag tag;
        try {
            tag = toTag(tree, new Place(null, null), slots, ignored);
        } catch (Skip e) {
            tag = StringTag.valueOf("(not saved: " + ignored.getLast() + ")"); // FORMAT
        }
        Component pretty = NbtUtils.toPrettyComponent(tag);
        return Codecs.adventure(pretty);
    }

    /** Waits for a running write and stops the worker (plugin off). */
    public void close() {
        waitForWrite();
        writer.shutdown();
    }
}
