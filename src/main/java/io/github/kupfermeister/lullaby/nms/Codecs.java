package io.github.kupfermeister.lullaby.nms;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.kupfermeister.lullaby.lua.Wire;
import io.papermc.paper.adventure.PaperAdventure;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import net.minecraft.commands.arguments.ParticleArgument;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.Removed;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Codec values: item stacks, their component patch and text components, converted
 * with vanilla's own codecs through {@link LuaOps}. Lua sees plain tables in codec shape (the same shape as datapack
 * JSON). Item stacks are kept as vanilla as possible: no checks of Lullaby's own; the patch is exactly vanilla's
 * (only the changes against the item type's defaults, removals as "!minecraft:food"). Main thread only.
 *
 * <p>Text: strings are literal text everywhere, exactly as vanilla's codecs read them (no MiniMessage or other markup).
 * tostring(component) gives vanilla's JSON text, which from_json(text, "component") reads back.
 */
public final class Codecs {

    /**
     * The wire's kind list, owned here: the library reads it once at startup ({@link #kindList}, J.wire_kinds) and
     * tags tables with these metatables by their 0-based index; its self-check stops loading if a kind has no Lua
     * side. Java code finds a kind's index by name ({@link #kindIndex}), never by a typed number.
     */
    private static final String[] KINDS = {"block_pos", "section_pos", "chunk_pos", "vec3", "yaw_pitch", "quaternion",
            "aabb", "bounding_box", "global_pos", "location", "block_state", "player", "world", "block", "item_stack",
            "component", "components", "entity", "chunk", "damage"};

    private static RegistryOps<Object> luaOps;
    private static RegistryOps<Tag> nbtOps;
    private static HolderLookup.Provider lookupFor;

    private Codecs() {
    }

    private static HolderLookup.Provider registries() {
        return MinecraftServer.getServer().registryAccess();
    }

    private static RegistryOps<Object> ops() {
        HolderLookup.Provider registries = registries();
        if (lookupFor != registries) {
            lookupFor = registries;
            luaOps = RegistryOps.create(LuaOps.INSTANCE, registries);
            nbtOps = RegistryOps.create(NbtOps.INSTANCE, registries);
        }
        return luaOps;
    }

    /** Vanilla's NBT ops with the server's registries (for saved data, SavedData.java). */
    static RegistryOps<Tag> nbt() {
        ops();
        return nbtOps;
    }

    /** Lua's ops with the server's registries. */
    static RegistryOps<Object> lua() {
        return ops();
    }

    private static <T> T get(DataResult<T> result) {
        return result.getOrThrow(IllegalArgumentException::new);
    }

    // ---- the wire's Java picture -> LuaOps values ----

    /**
     * Lua data as LuaOps values: tables become List (t[1..n], no holes, nothing else), Map or {@link
     * LuaOps#EMPTY_TABLE}; tagged small values become vanilla's codec shape; text components become their codec tree.
     */
    static Object plain(Object value, IntFunction<Object> slots) {
        if (value == null) return LuaOps.NONE;
        if (!(value instanceof Wire.Table t)) return value;
        if (t.kind >= 0) return kind(t, slots);
        if (t.hash.isEmpty()) {
            if (t.array.isEmpty()) return LuaOps.EMPTY_TABLE;
            List<Object> list = new ArrayList<>(t.array.size());
            for (int i = 0; i < t.array.size(); i++) {
                Object item = t.array.get(i);
                // FORMAT: list with a hole
                if (item == null) throw new IllegalArgumentException("the list has a hole at [" + (i + 1) + "]");
                list.add(plain(item, slots));
            }
            return list;
        }
        List<Object> sequence = sequence(t);
        if (sequence != null) {
            List<Object> list = new ArrayList<>(sequence.size());
            for (Object item : sequence) list.add(plain(item, slots));
            return list;
        }
        Map<Object, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < t.array.size(); i++) {
            if (t.array.get(i) != null) map.put((double) (i + 1), plain(t.array.get(i), slots));
        }
        for (Map.Entry<Object, Object> e : t.hash.entrySet()) map.put(plain(e.getKey(), slots), plain(e.getValue(), slots));
        return map;
    }

    /**
     * A table whose whole-number keys are exactly 1..n, partly or wholly in the hash part (LuaJIT puts {[1]=a} and
     * grown lists there), as its items in order; null if it is not such a list.
     */
    static List<Object> sequence(Wire.Table t) {
        int n = t.array.size() + t.hash.size();
        Object[] items = new Object[n];
        for (int i = 0; i < t.array.size(); i++) {
            if (t.array.get(i) == null) return null;
            items[i] = t.array.get(i);
        }
        for (Map.Entry<Object, Object> e : t.hash.entrySet()) {
            if (!(e.getKey() instanceof Double d) || d != Math.floor(d) || d < 1 || d > n) return null;
            int index = (int) (double) d - 1;
            if (items[index] != null) return null;
            items[index] = e.getValue();
        }
        for (Object item : items) if (item == null) return null;
        return List.of(items);
    }

    /** An item_stack placed inside a table (only a show_item hover takes one: item = <item_stack>). */
    private record ItemRef(ItemStack stack) {
    }

    /**
     * Makes the tree ready for a vanilla codec: a show_item hover's {@code item = <item_stack>} becomes vanilla's flat
     * fields (id, count, components; ); an item_stack anywhere else is an error.
     */
    static Object unwrap(Object tree) {
        if (tree instanceof ItemRef) {
            // FORMAT: an item_stack in a table where vanilla has no item
            throw new IllegalArgumentException("an item_stack can only go into a show_item hover (item = ...)");
        }
        if (tree instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(unwrap(item));
            return result;
        }
        if (tree instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            boolean showItem = "show_item".equals(map.get("action")) && map.get("item") instanceof ItemRef;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (showItem && "item".equals(e.getKey())) continue;
                result.put(e.getKey(), unwrap(e.getValue()));
            }
            if (showItem) {
                Object fields;
                try {
                    fields = get(ItemStack.CODEC.encodeStart(ops(), ((ItemRef) map.get("item")).stack()));
                } catch (IllegalArgumentException e) {
                    // FORMAT: an item vanilla can't show in a hover (e.g. count above 99)
                    throw new IllegalArgumentException("show_item hover: " + e.getMessage());
                }
                if (fields instanceof Map<?, ?> f) result.putAll(f);
            }
            return result;
        }
        return tree;
    }

    private static Object kind(Wire.Table t, IntFunction<Object> slots) {
        String kind = t.kind < KINDS.length ? KINDS[t.kind] : "?";
        try {
            return kindShape(kind, t, slots);
        } catch (ClassCastException | IndexOutOfBoundsException | NullPointerException e) {
            // FORMAT: a small value or component changed with rawset
            throw new IllegalArgumentException(article(kind) + " was changed with rawset and can't be used");
        }
    }

    private static Object kindShape(String kind, Wire.Table t, IntFunction<Object> slots) {
        switch (kind) {
            case "block_pos", "chunk_pos", "vec3", "quaternion" -> {
                return new ArrayList<>(t.array);
            }
            case "bounding_box" -> {
                List<Object> six = new ArrayList<>(((Wire.Table) t.array.get(0)).array);
                six.addAll(((Wire.Table) t.array.get(1)).array);
                return six;
            }
            case "global_pos" -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                map.put("dimension", t.array.get(0));
                map.put("pos", new ArrayList<>(((Wire.Table) t.array.get(1)).array));
                return map;
            }
            case "block_state" -> {
                // blocks.lua keeps the state number at [1] (vanilla's Block.getId)
                return get(BlockState.CODEC.encodeStart(ops(), Nms.state(((Number) t.array.get(0)).intValue())));
            }
            case "item_stack" -> {
                // lazy.lua puts the item's current slot at [true] while the table is on the wire
                return new ItemRef((ItemStack) slots.apply(((Number) t.hash.get(Boolean.TRUE)).intValue()));
            }
            case "component" -> {
                t.kind = -1; // a component is a plain table in codec shape
                return plain(t, slots);
            }
            case "components" -> {
                t.kind = -1; // vanilla's patch table read from an item: a plain table
                return plain(t, slots);
            }
            default -> {
                // FORMAT: a value that has no codec shape inside a codec table
                throw new IllegalArgumentException(article(kind) + " can't go inside this table");
            }
        }
    }

    /** Every wire kind, one per line, in index order (for the library, once at startup). */
    public static String kindList() {
        return String.join("\n", KINDS);
    }

    /** A wire kind's index by its name; an unknown name is a bug in Lullaby. */
    static int kindIndex(String name) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(name)) return i;
        throw new IllegalStateException("Lullaby bug: no wire kind " + name);
    }

    /** "a vec3", "an item_stack", "an entity": a kind name with its article, for error messages. */
    static String article(String kind) {
        return ("aeiou".indexOf(kind.charAt(0)) >= 0 ? "an " : "a ") + kind;
    }

    /** The wire kind's name ("vec3", "player", ...) for error messages. */
    static String kindName(int kind) {
        return kind >= 0 && kind < KINDS.length ? KINDS[kind] : "?";
    }

    /** Short text for an error message. */
    static String describe(Object value) {
        if (value == LuaOps.NONE) return "nil";
        if (value instanceof String s) return "\"" + s + "\"";
        if (value instanceof Double d) return numberText(d);
        if (value instanceof List || value instanceof Map || value == LuaOps.EMPTY_TABLE) return "a table";
        return String.valueOf(value);
    }

    static String numberText(double d) {
        if (d == Math.floor(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    /** The full id of a patch key ("custom_name", "!food" -> "minecraft:custom_name", "minecraft:food"). */
    private static String componentId(String key) {
        String id = key.startsWith("!") ? key.substring(1) : key;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    /**
     * Components holding raw NBT: shown as SNBT text ("{Count:5b}") so NBT number kinds (byte,
     * short, float, ...) survive the trip through Lua; read back with vanilla's SNBT parser. The table form comes
     * with the nbt type (build step 3b).
     */
    private static final Set<DataComponentType<?>> NBT_TYPES = Set.of(DataComponents.CUSTOM_DATA,
            DataComponents.ENTITY_DATA, DataComponents.BLOCK_ENTITY_DATA, DataComponents.BUCKET_ENTITY_DATA);

    /** A patch from Lua: SNBT text for the NBT components, codec shape (strings literal) for the rest. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DataComponentPatch patchParse(Object tree) {
        ops();
        Map<DataComponentType, Object> snbt = new LinkedHashMap<>();
        Object rest = tree;
        if (tree instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Identifier key = e.getKey() instanceof String k && !k.startsWith("!") ? Identifier.tryParse(componentId(k)) : null;
                DataComponentType type = key == null ? null : BuiltInRegistries.DATA_COMPONENT_TYPE.getValue(key);
                if (type != null && NBT_TYPES.contains(type) && e.getValue() instanceof String text) {
                    Tag tag;
                    try {
                        tag = TagParser.create(NbtOps.INSTANCE).parseFully(text);
                    } catch (CommandSyntaxException ex) {
                        throw new IllegalArgumentException(e.getKey() + ": " + ex.getMessage()); // FORMAT: bad SNBT
                    }
                    snbt.put(type, get(type.codecOrThrow().parse(nbtOps, tag)));
                } else {
                    copy.put(e.getKey(), e.getValue());
                }
            }
            rest = copy.isEmpty() ? LuaOps.EMPTY_TABLE : copy;
        }
        DataComponentPatch patch = get(DataComponentPatch.CODEC.parse(ops(), unwrap(rest)));
        if (snbt.isEmpty()) return patch;
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        builder.copy(patch);
        for (Map.Entry<DataComponentType, Object> e : snbt.entrySet()) builder.set(e.getKey(), e.getValue());
        return builder.build();
    }

    /** Vanilla's own item checks (the ones /give runs): count against max stack size, container contents, ... */
    private static ItemStack strict(ItemStack stack) {
        if (!stack.isEmpty()) get(ItemStack.validateStrict(stack));
        return stack;
    }

    /** Vanilla's patch as a Lua table: "minecraft:id" = value, "!minecraft:id" = {} for removed components. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Map<Object, Object> patchOut(DataComponentPatch patch) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<DataComponentType<?>, Object> e : patch.map.entrySet()) {
            DataComponentType type = e.getKey();
            if (type.isTransient()) continue; // vanilla's patch codec leaves these out too
            String id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
            Object value = e.getValue(); // the value itself, or vanilla's Removed marker
            if (!Removed.isNotRemoved(value)) {
                result.put("!" + id, LuaOps.EMPTY_TABLE);
            } else if (NBT_TYPES.contains(type)) {
                result.put(id, get(type.codecOrThrow().encodeStart(nbtOps, value)).toString());
            } else {
                result.put(id, get(type.codecOrThrow().encodeStart(ops(), value)));
            }
        }
        return result;
    }

    // ---- item stacks ----

    /** Vanilla's item text "diamond_sword[damage=5]" (count 1). The whole text must be used. */
    public static ItemStack itemParse(String text) {
        StringReader reader = new StringReader(text);
        ItemInput input;
        try {
            input = new ItemParser(registries()).parse(reader);
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        // FORMAT: text left after the item text
        if (reader.canRead()) throw new IllegalArgumentException("unexpected text after the item: " + reader.getRemaining());
        try {
            return input.createItemStack(1); // runs vanilla's own checks, like /give
        } catch (CommandSyntaxException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    /** An item stack from a codec table { id = , count = , components = } (vanilla's ItemStack.CODEC). */
    public static ItemStack itemFromTable(Object wire, IntFunction<Object> slots) {
        Object tree = plain(wire, slots);
        Object components = null;
        if (tree instanceof Map<?, ?> map && map.containsKey("components")) {
            Map<Object, Object> copy = new LinkedHashMap<>(map);
            components = copy.remove("components");
            tree = copy;
        }
        ItemStack stack = get(ItemStack.CODEC.parse(ops(), unwrap(tree)));
        if (components != null) stack = new ItemStack(stack.typeHolder(), stack.getCount(), patchParse(components));
        return strict(stack);
    }

    /**
     * A copy of {@code base} with some parts replaced (each null: unchanged). As vanilla: a new id keeps the patch
     * (vanilla's transmuteCopy), the count is taken as it is (0 or less: the empty stack).
     */
    public static ItemStack itemBuild(ItemStack base, Object id, Object count, Object components, IntFunction<Object> slots) {
        Holder<Item> item = base.typeHolder();
        if (id != null) {
            // FORMAT: item id is not text
            if (!(id instanceof String text)) throw new IllegalArgumentException("id must be an item id text");
            Identifier key = Identifier.tryParse(text);
            Optional<Holder.Reference<Item>> found = key == null ? Optional.empty() : BuiltInRegistries.ITEM.get(key);
            // FORMAT: unknown item id
            item = found.orElseThrow(() -> new IllegalArgumentException("unknown item " + text));
        }
        int amount = base.getCount();
        if (count != null) {
            if (!(count instanceof Number n)) throw new IllegalArgumentException("count must be a number"); // FORMAT
            amount = new LuaOps.Strict(n).intValue();
        }
        DataComponentPatch patch = base.getComponentsPatch();
        if (components != null) patch = patchParse(plain(components, slots));
        return strict(new ItemStack(item, amount, patch));
    }

    public static String itemId(ItemStack stack) {
        return stack.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:air");
    }

    /** The patch as wire bytes for Lua. */
    public static ByteBuffer itemComponents(ItemStack stack) {
        return Wire.write(patchOut(stack.getComponentsPatch()));
    }

    /** Vanilla's item text for the stack: "minecraft:diamond_sword[minecraft:damage=5,!minecraft:food]" (no count). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static String itemText(ItemStack stack) {
        ops();
        StringBuilder text = new StringBuilder(itemId(stack));
        DataComponentPatch patch = stack.getComponentsPatch();
        boolean first = true;
        for (Map.Entry<DataComponentType<?>, Object> e : patch.map.entrySet()) {
            DataComponentType type = e.getKey();
            if (type.isTransient()) continue;
            text.append(first ? '[' : ',');
            first = false;
            String id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type).toString();
            Object value = e.getValue(); // the value itself, or vanilla's Removed marker
            if (!Removed.isNotRemoved(value)) {
                text.append('!').append(id);
            } else {
                text.append(id).append('=').append(get(type.codecOrThrow().encodeStart(nbtOps, value)));
            }
        }
        if (!first) text.append(']');
        return text.toString();
    }

    public static int itemCount(ItemStack stack) {
        return stack.getCount();
    }

    public static boolean itemEmpty(ItemStack stack) {
        return stack.isEmpty();
    }

    public static boolean itemMatches(ItemStack a, ItemStack b) {
        return ItemStack.matches(a, b);
    }

    // ---- text components (plain Lua tables in vanilla codec shape) ----

    /** A vanilla component from a Lua table in codec shape (strings are literal text, as vanilla). */
    public static Component componentFromTable(Object wire, IntFunction<Object> slots) {
        return get(ComponentSerialization.CODEC.parse(ops(), unwrap(plain(wire, slots))));
    }

    /** A component as vanilla's JSON text (tostring of a component); from_json(text, "component") reads it back. */
    public static String componentText(Component text) {
        ops();
        return get(ComponentSerialization.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries()), text))
                .toString();
    }

    /**
     * A component as wire bytes of its codec table (never a bare string: plain text becomes { text = }). A show_item
     * hover gets {@code item = {[true] = slot}} (a new item stack in a slot) instead of vanilla's flat id/count/
     * components; the second value is how many, so lazy.lua only looks for them when there are any.
     */
    public static Object[] componentOut(Component text, ToIntFunction<Object> addSlot) {
        int[] items = {0};
        Object tree = componentTree(text, addSlot, items);
        return new Object[]{Wire.write(tree), items[0]};
    }

    /** A component as its codec table (Java's picture of it, see componentOut); {@code items} counts the item slots. */
    static Object componentTree(Component text, ToIntFunction<Object> addSlot, int[] items) {
        Object tree = get(ComponentSerialization.CODEC.encodeStart(ops(), text));
        if (tree instanceof String s) {
            Map<Object, Object> map = new LinkedHashMap<>();
            map.put("text", s);
            tree = map;
        }
        return itemsOut(tree, addSlot, items);
    }

    /** from_json's result: a component as {@link #componentOut} gives it, or null if it is not a component. */
    public static Object[] componentOutIfText(Object value, ToIntFunction<Object> addSlot) {
        return value instanceof Component text ? componentOut(text, addSlot) : null;
    }

    private static Object itemsOut(Object tree, ToIntFunction<Object> addSlot, int[] items) {
        if (tree instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(itemsOut(item, addSlot, items));
            return result;
        }
        if (!(tree instanceof Map<?, ?> map)) return tree;
        Map<Object, Object> result = new LinkedHashMap<>();
        if ("show_item".equals(map.get("action"))) {
            Map<Object, Object> fields = new LinkedHashMap<>(map);
            fields.remove("action");
            ItemStack stack = get(ItemStack.CODEC.parse(ops(), fields));
            Map<Object, Object> ref = new LinkedHashMap<>();
            ref.put(Boolean.TRUE, addSlot.applyAsInt(stack));
            items[0]++;
            result.put("action", "show_item");
            result.put("item", ref);
            return result;
        }
        for (Map.Entry<?, ?> e : map.entrySet()) result.put(e.getKey(), itemsOut(e.getValue(), addSlot, items));
        return result;
    }

    public static Component vanilla(net.kyori.adventure.text.Component text) {
        return PaperAdventure.asVanilla(text);
    }

    public static net.kyori.adventure.text.Component adventure(Component text) {
        return PaperAdventure.asAdventure(text);
    }

    /**
     * A tree of Java values (maps, lists, plain values and vanilla values inside, e.g. entity.display_data) as plain
     * data for the wire: a value that becomes a Lua value is a marker table {[true] = kind, [1] = ..., ...} (vec3 and
     * quaternion: their numbers; block_state: its state number; item_stack: a new slot; component: its codec table),
     * which I.from_java's "tree" (entities.lua) turns into that value.
     */
    public static Object treeOut(Object v, ToIntFunction<Object> addSlot) {
        if (v instanceof Map<?, ?> m) {
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(e.getKey(), treeOut(e.getValue(), addSlot));
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object item : l) out.add(treeOut(item, addSlot));
            return out;
        }
        if (v instanceof org.joml.Vector3fc) return marker("vec3", DisplayData.parts(v));
        if (v instanceof org.joml.Quaternionfc) return marker("quaternion", DisplayData.parts(v));
        if (v instanceof BlockState s) return marker("block_state", Block.getId(s));
        if (v instanceof ItemStack s) return marker("item_stack", addSlot.applyAsInt(s.copy()));
        if (v instanceof Component c) return marker("component", componentTree(c, addSlot, new int[1]));
        return v;
    }

    private static Map<Object, Object> marker(String kind, Object... parts) {
        Map<Object, Object> m = new LinkedHashMap<>();
        m.put(Boolean.TRUE, kind);
        for (int i = 0; i < parts.length; i++) m.put((double) (i + 1), parts[i]);
        return m;
    }

    private static Map<Object, Object> marker(String kind, double[] parts) {
        Object[] boxed = new Object[parts.length];
        for (int i = 0; i < parts.length; i++) boxed[i] = parts[i];
        return marker(kind, boxed);
    }

    // ---- block states and particles from Lua ----

    /** block_state{ Name = , Properties = } (vanilla's BlockState.CODEC) to vanilla's state number. */
    public static int blockStateFromTable(Object wire, IntFunction<Object> slots) {
        return Block.getId(get(BlockState.CODEC.parse(ops(), unwrap(plain(wire, slots)))));
    }

    /**
     * A particle from Lua: vanilla's command text ("flame", "dust{color:[1,0,0],scale:1}", as /particle takes it) or
     * a codec table { type = "dust", color = , scale = } (ParticleTypes.CODEC).
     */
    public static ParticleOptions particle(Object wire, IntFunction<Object> slots) {
        if (wire instanceof String text) {
            StringReader reader = new StringReader(text);
            ParticleOptions options;
            try {
                options = ParticleArgument.readParticle(reader, registries());
            } catch (CommandSyntaxException e) {
                throw new IllegalArgumentException("particle: " + e.getMessage()); // FORMAT: bad particle text
            }
            // FORMAT: text left after the particle text
            if (reader.canRead()) throw new IllegalArgumentException("particle: unexpected text " + reader.getRemaining());
            return options;
        }
        if (wire instanceof Wire.Table) return get(ParticleTypes.CODEC.parse(ops(), unwrap(plain(wire, slots))));
        // FORMAT: particle of the wrong type
        throw new IllegalArgumentException("particle must be text like \"flame\" or a table { type = \"dust\", ... }");
    }

    // ---- from_json ----

    /** Datapack JSON with vanilla's own meaning (strings are literal text, unknown keys ignored, as vanilla). */
    public static Object fromJson(String json, String type) {
        ops();
        DynamicOps<JsonElement> jsonOps = RegistryOps.create(JsonOps.INSTANCE, registries());
        Codec<?> codec = switch (type) {
            case "item_stack" -> ItemStack.CODEC;
            case "component" -> ComponentSerialization.CODEC;
            // FORMAT: from_json type not supported
            default -> throw new IllegalArgumentException("from_json: unknown type " + type + " (item_stack, component)");
        };
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("from_json: not valid JSON (" + e.getMessage() + ")"); // FORMAT
        }
        return get(codec.parse(jsonOps, element));
    }
}
