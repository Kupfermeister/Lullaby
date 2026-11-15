package io.github.kupfermeister.lullaby.nms;

import com.google.gson.JsonParser;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import io.github.kupfermeister.lullaby.lua.Wire;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.IntFunction;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Codec values (build step 3a, Kupfermeister 2026-11-15): item stacks, their component patch and text components, converted
 * with vanilla's own codecs through {@link LuaOps}. Lua sees plain tables in codec shape (the same shape as datapack
 * JSON). Item stacks are kept as vanilla as possible: no checks of Lullaby's own; the patch is exactly vanilla's
 * (only the changes against the item type's defaults, removals as "!minecraft:food"). Main thread only.
 *
 * <p>MiniMessage (Kupfermeister 2026-11-15, TEXT_POSITIONS in lib/types.lua): in Lua tables a string at a text position is
 * MiniMessage, and reading gives MiniMessage back. Built so far: custom_name, item_name, lore. The other positions
 * (books, signs, trims, ...) still take vanilla's codec shape and come with build step 3b.
 */
public final class Codecs {

    /** The wire's kind list; lib/wire.lua sends tables with these metatables tagged by index, in this order. */
    private static final String[] KINDS = {"block_pos", "section_pos", "chunk_pos", "vec3", "yaw_pitch", "quaternion",
            "aabb", "bounding_box", "global_pos", "location", "block_state", "player", "world", "block", "item_stack",
            "component", "components"};

    private static RegistryOps<Object> luaOps;
    private static RegistryOps<net.minecraft.nbt.Tag> nbtOps;
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
    private static List<Object> sequence(Wire.Table t) {
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

    /** A component placed inside a table: already in codec shape, never read again as MiniMessage. */
    private record Literal(Object tree) {
    }

    /** Takes the {@link Literal} wrappers out again, right before a vanilla codec reads the tree. */
    private static Object unwrap(Object tree) {
        if (tree instanceof Literal l) return l.tree();
        if (tree instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) result.add(unwrap(item));
            return result;
        }
        if (tree instanceof Map<?, ?> map) {
            Map<Object, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) result.put(e.getKey(), unwrap(e.getValue()));
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
            throw new IllegalArgumentException("a " + kind + " was changed with rawset and can't be used");
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
            case "component" -> {
                Component text = (Component) slots.apply(((Number) t.array.get(0)).intValue());
                return new Literal(get(ComponentSerialization.CODEC.encodeStart(ops(), text)));
            }
            case "components" -> {
                t.kind = -1; // vanilla's patch table read from an item: a plain table
                return plain(t, slots);
            }
            default -> {
                // FORMAT: a value that has no codec shape inside a codec table
                throw new IllegalArgumentException("a " + kind + " can't go inside this table");
            }
        }
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

    // ---- MiniMessage at text positions ----

    private static Component mini(String text) {
        return PaperAdventure.asVanilla(MiniMessage.miniMessage().deserialize(text));
    }

    private static String mini(Component text) {
        return MiniMessage.miniMessage().serialize(PaperAdventure.asAdventure(text));
    }

    /** A text position's value from Lua: a string is MiniMessage, anything else is vanilla's codec shape. */
    private static Object textIn(Object value) {
        if (value instanceof Literal) return value;
        if (value instanceof String s) return get(ComponentSerialization.CODEC.encodeStart(ops(), mini(s)));
        if (value instanceof Map<?, ?> m) return componentTableIn(m);
        return value;
    }

    /** Inside a component codec table "text" stays literal (vanilla); strings in "extra" are MiniMessage. */
    private static Object componentTableIn(Map<?, ?> table) {
        Object extra = table.get("extra");
        if (!(extra instanceof List<?> list)) return table;
        Map<Object, Object> copy = new LinkedHashMap<>(table);
        List<Object> lines = new ArrayList<>();
        for (Object item : list) lines.add(textIn(item));
        copy.put("extra", lines);
        return copy;
    }

    /** The full id of a patch key ("custom_name", "!food" -> "minecraft:custom_name", "minecraft:food"). */
    private static String componentId(String key) {
        String id = key.startsWith("!") ? key.substring(1) : key;
        return id.indexOf(':') < 0 ? "minecraft:" + id : id;
    }

    private static Object patchIn(Object patch) {
        if (!(patch instanceof Map<?, ?> map)) return patch;
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object value = e.getValue();
            if (e.getKey() instanceof String key && !key.startsWith("!")) {
                switch (componentId(key)) {
                    case "minecraft:custom_name", "minecraft:item_name" -> value = textIn(value);
                    case "minecraft:lore" -> {
                        if (value instanceof List<?> lines) {
                            List<Object> converted = new ArrayList<>();
                            for (Object line : lines) converted.add(textIn(line));
                            value = converted;
                        }
                    }
                    default -> {
                    }
                }
            }
            result.put(e.getKey(), value);
        }
        return result;
    }

    /**
     * Components holding raw NBT (Kupfermeister 2026-11-15): shown as SNBT text ("{Count:5b}") so NBT number kinds (byte,
     * short, float, ...) survive the trip through Lua; read back with vanilla's SNBT parser. The table form comes
     * with the nbt type (build step 3b).
     */
    private static final java.util.Set<DataComponentType<?>> NBT_TYPES = java.util.Set.of(DataComponents.CUSTOM_DATA,
            DataComponents.ENTITY_DATA, DataComponents.BLOCK_ENTITY_DATA, DataComponents.BUCKET_ENTITY_DATA);

    /** A patch from Lua: SNBT text for the NBT components, codec shape (MiniMessage at text positions) for the rest. */
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
                    net.minecraft.nbt.Tag tag;
                    try {
                        tag = net.minecraft.nbt.TagParser.create(NbtOps.INSTANCE).parseFully(text);
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
        DataComponentPatch patch = get(DataComponentPatch.CODEC.parse(ops(), unwrap(patchIn(rest))));
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
            if (!net.minecraft.core.component.Removed.isNotRemoved(value)) {
                result.put("!" + id, LuaOps.EMPTY_TABLE);
            } else if (NBT_TYPES.contains(type)) {
                result.put(id, get(type.codecOrThrow().encodeStart(nbtOps, value)).toString());
            } else if (type == DataComponents.CUSTOM_NAME || type == DataComponents.ITEM_NAME) {
                result.put(id, mini((Component) value));
            } else if (type == DataComponents.LORE) {
                List<Object> lines = new ArrayList<>();
                for (Component line : ((ItemLore) value).lines()) lines.add(mini(line));
                result.put(id, lines.isEmpty() ? LuaOps.EMPTY_TABLE : lines);
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
    public static java.nio.ByteBuffer itemComponents(ItemStack stack) {
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
            if (!net.minecraft.core.component.Removed.isNotRemoved(value)) {
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

    // ---- text components ----

    public static Component componentMini(String text) {
        return mini(text);
    }

    public static String componentText(Component text) {
        return mini(text);
    }

    public static Component componentFromTable(Object wire, IntFunction<Object> slots) {
        return get(ComponentSerialization.CODEC.parse(ops(), unwrap(textIn(plain(wire, slots)))));
    }

    public static net.kyori.adventure.text.Component adventure(Component text) {
        return PaperAdventure.asAdventure(text);
    }

    // ---- from_json ----

    /** Datapack JSON with vanilla's own meaning (strings are literal text, unknown keys ignored, as vanilla). */
    public static Object fromJson(String json, String type) {
        ops();
        DynamicOps<com.google.gson.JsonElement> jsonOps = RegistryOps.create(JsonOps.INSTANCE, registries());
        Codec<?> codec = switch (type) {
            case "item_stack" -> ItemStack.CODEC;
            case "component" -> ComponentSerialization.CODEC;
            // FORMAT: from_json type not supported
            default -> throw new IllegalArgumentException("from_json: unknown type " + type + " (item_stack, component)");
        };
        com.google.gson.JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("from_json: not valid JSON (" + e.getMessage() + ")"); // FORMAT
        }
        return get(codec.parse(jsonOps, element));
    }
}
