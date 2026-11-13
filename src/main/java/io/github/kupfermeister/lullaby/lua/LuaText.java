package io.github.kupfermeister.lullaby.lua;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import party.iroiro.luajava.Lua;

/**
 * Text between Java and Lua as real UTF-8. LuaJava's own {@code push(String)} / {@code toString(int)} use Java's
 * "modified UTF-8", which turns emoji and other 4-byte characters into something Lua and the client do not read as
 * the same text; byte buffers keep the exact bytes.
 */
public final class LuaText {

    private LuaText() {
    }

    /** The Lua string at a stack position; null if the value there is not a string (or number). */
    public static String string(Lua L, int index) {
        if (!L.isString(index)) return null;
        ByteBuffer bytes = L.toBuffer(index);
        return bytes == null ? null : StandardCharsets.UTF_8.decode(bytes).toString();
    }

    public static void push(Lua L, String text) {
        L.push(ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
    }
}
