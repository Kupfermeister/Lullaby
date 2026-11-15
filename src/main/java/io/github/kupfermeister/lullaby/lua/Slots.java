package io.github.kupfermeister.lullaby.lua;

/**
 * Java's side of Lullaby objects: each Lua object (player, world, event, ...) carries a slot number, and this array
 * holds what the number stands for (a UUID, a world key, a Paper event). Lua sends numbers instead of LuaJava's own
 * Java objects, which cost a JNI reference and reflection per use (see tools/docs/design/types.lua).
 *
 * <p>A slot lives as long as its Lua object: when Lua's garbage collector frees the object, the library collects the
 * slot number and hands all freed numbers back in one call per tick ({@link #free}). One Slots per Lua state; a
 * reload throws both away together. Main thread only.
 */
final class Slots {

    private Object[] objects = new Object[256];
    private int[] free = new int[64];
    private int freeCount;
    private int next = 1; // 0 is never used, so a missing number can't look like a slot

    int add(Object object) {
        int slot;
        if (freeCount > 0) {
            slot = free[--freeCount];
        } else {
            slot = next++;
            if (slot >= objects.length) objects = java.util.Arrays.copyOf(objects, objects.length * 2);
        }
        objects[slot] = object;
        return slot;
    }

    /** What a slot stands for; a slot the library never got or already freed is a bug in the library. */
    @SuppressWarnings("unchecked")
    <T> T get(int slot) {
        Object object = slot > 0 && slot < next ? objects[slot] : null;
        if (object == null) throw new IllegalStateException("Lullaby bug: unknown slot " + slot);
        return (T) object;
    }

    /** Frees the slots in a list like "3\n7\n12" (sent by the library once per tick). */
    void free(String list) {
        int value = 0;
        for (int i = 0, n = list.length(); i <= n; i++) {
            char c = i < n ? list.charAt(i) : '\n';
            if (c == '\n') {
                if (value > 0) release(value);
                value = 0;
            } else {
                value = value * 10 + (c - '0');
            }
        }
    }

    private void release(int slot) {
        objects[slot] = null;
        if (freeCount == free.length) free = java.util.Arrays.copyOf(free, free.length * 2);
        free[freeCount++] = slot;
    }
}
