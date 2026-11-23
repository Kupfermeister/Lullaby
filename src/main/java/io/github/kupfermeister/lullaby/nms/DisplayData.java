package io.github.kupfermeister.lullaby.nms;

import com.mojang.math.Transformation;
import io.github.kupfermeister.lullaby.lua.Wire;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Brightness;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * display_data: the settings of display entities (text, item and block displays) as ONE table, in modify_entity{ },
 * blueprint{ }, summon{ } and clientside_summon{ }, and read back as entity.display_data in the same shape. Owns the
 * shape (the docs page "display_data" in entities.lua describes it). Names are vanilla's own NBT names, with the parts
 * that belong together in inner tables (transformation, interpolation, brightness, shadow, size, text, item).
 *
 * <p>Every entry is optional and changes only what it names (transformation = { scale = v } keeps the rotation). An
 * entry the display doesn't have (text on a block display) is checked and then ignored; display_data on an entity that
 * is no display is ignored. Main thread only.
 */
final class DisplayData {

    private static final Map<String, Display.BillboardConstraints> BILLBOARDS = Fields.byName(
            Display.BillboardConstraints::getSerializedName, Display.BillboardConstraints.values());
    private static final Map<String, Display.TextDisplay.Align> ALIGNS = Fields.byName(
            Display.TextDisplay.Align::getSerializedName, Display.TextDisplay.Align.values());
    private static final Map<String, ItemDisplayContext> ITEM_DISPLAYS = Fields.byName(
            ItemDisplayContext::getSerializedName, ItemDisplayContext.values());
    private static final int ITEM = Codecs.kindIndex("item_stack");
    private static final int BLOCK_STATE = Codecs.kindIndex("block_state");

    private DisplayData() {
    }

    /**
     * Checks display_data and returns the change, or null if {@code e} is no display (then it is ignored, after the
     * values were checked). Nothing changes before the returned change runs.
     */
    static Runnable prepare(Fields a, String field, Entity e, IntFunction<Object> slots) {
        Fields d = a.table(field);
        if (d == null) throw a.wrong(field, "a table { ... }", null);
        List<java.util.function.Consumer<Display>> all = new ArrayList<>();
        List<java.util.function.Consumer<Display.TextDisplay>> text = new ArrayList<>();
        List<java.util.function.Consumer<Display.ItemDisplay>> item = new ArrayList<>();
        BlockState block = null;

        Fields t = d.table("transformation");
        if (t != null) {
            float[] translation = t.has("translation") ? floats(t.vec3("translation")) : null;
            float[] left = t.has("left_rotation") ? floats(t.quaternion("left_rotation")) : null;
            float[] scale = t.has("scale") ? floats(t.vec3("scale")) : null;
            float[] right = t.has("right_rotation") ? floats(t.quaternion("right_rotation")) : null;
            t.done();
            all.add(x -> {
                Transformation now = Display.createTransformation(x.getEntityData());
                x.setTransformation(new Transformation(
                        translation != null ? new Vector3f(translation[0], translation[1], translation[2]) : now.translation(),
                        left != null ? new Quaternionf(left[0], left[1], left[2], left[3]) : now.leftRotation(),
                        scale != null ? new Vector3f(scale[0], scale[1], scale[2]) : now.scale(),
                        right != null ? new Quaternionf(right[0], right[1], right[2], right[3]) : now.rightRotation()));
            });
        }
        Fields i = d.table("interpolation");
        if (i != null) {
            boolean hasDuration = i.has("duration"), hasStart = i.has("start");
            int duration = i.whole("duration", 0, 0, Integer.MAX_VALUE);
            int start = i.whole("start", 0);
            i.done();
            if (hasDuration) all.add(x -> x.setTransformationInterpolationDuration(duration));
            // vanilla sends the start every time it is set, also when the number is the same: each start begins anew
            if (hasStart) all.add(x -> x.setTransformationInterpolationDelay(start));
        }
        if (d.has("teleport_duration")) {
            int ticks = d.whole("teleport_duration", 0, 0, 59); // vanilla's range (it clamps saved values to it)
            all.add(x -> x.getEntityData().set(Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID, ticks));
        }
        if (d.has("billboard")) {
            Display.BillboardConstraints b = d.choice("billboard", BILLBOARDS, null);
            all.add(x -> x.setBillboardConstraints(b));
        }
        if (d.has("brightness")) {
            Brightness brightness;
            if (Boolean.FALSE.equals(d.raw("brightness"))) {
                brightness = null; // the world's own light
            } else {
                Fields b = d.table("brightness");
                b.required("block", "0 to 15");
                b.required("sky", "0 to 15");
                brightness = new Brightness(b.whole("block", 0, 0, 15), b.whole("sky", 0, 0, 15));
                b.done();
            }
            all.add(x -> x.setBrightnessOverride(brightness));
        }
        if (d.has("view_range")) {
            float range = (float) finite(d, "view_range");
            all.add(x -> x.setViewRange(range));
        }
        Fields s = d.table("shadow");
        if (s != null) {
            boolean hasRadius = s.has("radius"), hasStrength = s.has("strength");
            float radius = (float) finite(s, "radius"), strength = (float) finite(s, "strength");
            s.done();
            if (hasRadius) all.add(x -> x.setShadowRadius(radius));
            if (hasStrength) all.add(x -> x.setShadowStrength(strength));
        }
        Fields z = d.table("size");
        if (z != null) {
            boolean hasWidth = z.has("width"), hasHeight = z.has("height");
            float width = (float) finite(z, "width"), height = (float) finite(z, "height");
            z.done();
            if (hasWidth) all.add(x -> x.setWidth(width));
            if (hasHeight) all.add(x -> x.setHeight(height));
        }
        if (d.has("glow_color")) {
            int color = Boolean.FALSE.equals(d.raw("glow_color")) ? -1 : d.whole("glow_color", 0, 0, 0xFFFFFF);
            all.add(x -> x.setGlowColorOverride(color));
        }

        Fields tx = d.table("text");
        if (tx != null) {
            if (tx.has("text")) {
                Component c = Entities.text(tx, "text", slots);
                text.add(x -> x.setText(c == null ? Component.empty() : c));
            }
            if (tx.has("line_width")) {
                int width = tx.whole("line_width", 0, 0, Integer.MAX_VALUE);
                text.add(x -> x.getEntityData().set(Display.TextDisplay.DATA_LINE_WIDTH_ID, width));
            }
            if (tx.has("background")) {
                int argb = argb(tx, "background");
                text.add(x -> x.getEntityData().set(Display.TextDisplay.DATA_BACKGROUND_COLOR_ID, argb));
            }
            if (tx.has("opacity")) {
                byte opacity = (byte) tx.whole("opacity", 0, 0, 255);
                text.add(x -> x.setTextOpacity(opacity));
            }
            byte set = 0, clear = 0;
            for (var flag : List.of(Map.entry("shadow", Display.TextDisplay.FLAG_SHADOW),
                    Map.entry("see_through", Display.TextDisplay.FLAG_SEE_THROUGH),
                    Map.entry("default_background", Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND))) {
                if (!tx.has(flag.getKey())) continue;
                if (tx.bool(flag.getKey(), false)) set |= flag.getValue();
                else clear |= flag.getValue();
            }
            if (tx.has("alignment")) {
                Display.TextDisplay.Align align = tx.choice("alignment", ALIGNS, null);
                clear |= Display.TextDisplay.FLAG_ALIGN_LEFT | Display.TextDisplay.FLAG_ALIGN_RIGHT;
                if (align == Display.TextDisplay.Align.LEFT) set |= Display.TextDisplay.FLAG_ALIGN_LEFT;
                if (align == Display.TextDisplay.Align.RIGHT) set |= Display.TextDisplay.FLAG_ALIGN_RIGHT;
            }
            tx.done();
            if (set != 0 || clear != 0) {
                byte on = set, off = clear;
                text.add(x -> x.setFlags((byte) ((x.getFlags() & ~off) | on)));
            }
        }
        Fields it = d.table("item");
        if (it != null) {
            if (it.has("item")) {
                ItemStack stack = item(it, slots);
                item.add(x -> x.setItemStack(stack.copy()));
            }
            if (it.has("display")) {
                ItemDisplayContext context = it.choice("display", ITEM_DISPLAYS, null);
                item.add(x -> x.setItemTransform(context));
            }
            it.done();
        }
        if (d.has("block_state")) block = blockState(d, slots);
        d.done();

        if (!(e instanceof Display display)) return null;
        BlockState state = block;
        return () -> {
            for (var change : all) change.accept(display);
            if (display instanceof Display.TextDisplay x) for (var change : text) change.accept(x);
            if (display instanceof Display.ItemDisplay x) for (var change : item) change.accept(x);
            if (display instanceof Display.BlockDisplay x && state != null) x.setBlockState(state);
        };
    }

    /**
     * entity.display_data: every setting, in the same shape display_data takes (so it can be given back as it is);
     * null for an entity that is no display. Values that become Lua values (vec3, quaternion, component, item_stack,
     * block_state) stay Java objects here; {@link Codecs#treeOut} marks them for the library.
     */
    static Map<String, Object> read(Entity e) {
        if (!(e instanceof Display d)) return null;
        var data = d.getEntityData();
        Map<String, Object> out = new LinkedHashMap<>();
        Transformation t = Display.createTransformation(data);
        out.put("transformation", map("translation", t.translation(), "left_rotation", t.leftRotation(),
                "scale", t.scale(), "right_rotation", t.rightRotation()));
        out.put("interpolation", map("duration", d.getTransformationInterpolationDuration(),
                "start", d.getTransformationInterpolationDelay()));
        out.put("teleport_duration", data.get(Display.DATA_POS_ROT_INTERPOLATION_DURATION_ID));
        out.put("billboard", d.getBillboardConstraints().getSerializedName());
        Brightness b = d.getBrightnessOverride();
        out.put("brightness", b == null ? Boolean.FALSE : map("block", b.block(), "sky", b.sky()));
        out.put("view_range", (double) d.getViewRange());
        out.put("shadow", map("radius", (double) d.getShadowRadius(), "strength", (double) d.getShadowStrength()));
        out.put("size", map("width", (double) d.getWidth(), "height", (double) d.getHeight()));
        int glow = d.getGlowColorOverride();
        out.put("glow_color", glow == -1 ? Boolean.FALSE : (Object) glow);
        if (d instanceof Display.TextDisplay x) {
            byte flags = x.getFlags();
            out.put("text", map("text", x.getText(), "line_width", x.getLineWidth(),
                    "background", (double) (x.getBackgroundColor() & 0xFFFFFFFFL), "opacity", x.getTextOpacity() & 0xFF,
                    "shadow", (flags & Display.TextDisplay.FLAG_SHADOW) != 0,
                    "see_through", (flags & Display.TextDisplay.FLAG_SEE_THROUGH) != 0,
                    "default_background", (flags & Display.TextDisplay.FLAG_USE_DEFAULT_BACKGROUND) != 0,
                    "alignment", Display.TextDisplay.getAlign(flags).getSerializedName()));
        }
        if (d instanceof Display.ItemDisplay x) {
            out.put("item", map("item", x.getItemStack().copy(), "display", x.getItemTransform().getSerializedName()));
        }
        if (d instanceof Display.BlockDisplay x) out.put("block_state", x.getBlockState());
        return out;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    private static float[] floats(double[] v) {
        float[] f = new float[v.length];
        for (int i = 0; i < v.length; i++) f[i] = (float) v[i];
        return f;
    }

    private static double finite(Fields a, String f) {
        double d = a.number(f, 0);
        if (!Double.isFinite(d)) throw a.wrong(f, "a finite number", a.raw(f));
        return d;
    }

    /** A color as one ARGB number (0x40000000 is a see-through black): 0 to 0xFFFFFFFF, or a negative int. */
    private static int argb(Fields a, String f) {
        double d = a.number(f, 0);
        // FORMAT: a color number out of range
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > 0xFFFFFFFFL)
            throw a.wrong(f, "a color number like 0x40000000 (alpha, red, green, blue)", a.raw(f));
        return (int) (long) d;
    }

    /** An item: an item_stack or vanilla's item text ("diamond_sword[damage=5]"). */
    private static ItemStack item(Fields a, IntFunction<Object> slots) {
        Object v = a.raw("item");
        if (v instanceof String s) {
            try {
                return Codecs.itemParse(s);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(a.what() + ": item: " + e.getMessage());
            }
        }
        // lazy.lua puts an item_stack's current slot at [true] while the table is on the wire
        if (v instanceof Wire.Table t && t.kind == ITEM) return (ItemStack) slots.apply(((Number) t.hash.get(Boolean.TRUE)).intValue());
        throw a.wrong("item", "an item_stack or item text like \"diamond_sword\"", v);
    }

    /** A block state: a block_state value or vanilla's text ("oak_stairs[facing=east]"). */
    private static BlockState blockState(Fields a, IntFunction<Object> slots) {
        Object v = a.raw("block_state");
        if (v instanceof String s) {
            try {
                return Nms.state(Nms.blockStateParse(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(a.what() + ": " + e.getMessage());
            }
        }
        if (v instanceof Wire.Table t && t.kind == BLOCK_STATE) return Nms.state(((Number) t.array.get(0)).intValue());
        throw a.wrong("block_state", "a block_state or text like \"oak_stairs[facing=east]\"", v);
    }

    /** For {@link Codecs#treeOut}: a vec3 or quaternion of floats as plain numbers. */
    static double[] parts(Object v) {
        if (v instanceof Vector3fc x) return new double[]{x.x(), x.y(), x.z()};
        Quaternionfc q = (Quaternionfc) v;
        return new double[]{q.x(), q.y(), q.z(), q.w()};
    }
}
