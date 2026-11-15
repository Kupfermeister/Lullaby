package io.github.kupfermeister.lullaby.error;

import io.github.kupfermeister.lullaby.nms.Nms;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;

/**
 * Shows script errors and warnings to every online op in chat and in the console, in the same format:
 * <pre>
 * ⚠ Error in scripts/lobby/join.lua on line 12                      red (a warning: yellow, "Warning")
 *   via PlayerJoinEvent (on) → greet                                gray
 *   the Lua error message                                           gray
 *   37 times in 10s                                                 gray, only in a repeat summary
 * </pre>
 * The console version has no symbols ("->" instead of "→", no "⚠"), because many consoles (Windows' default code
 * page) show them as garbage like "ÔÜá"; there the details are the normal text color and a warning's first line too
 * Chat keeps the symbols and colors above. The head names no function: the via line already ends
 * with it.
 * The same error (kind, file, line, message) repeating within the window is only counted; when the window ends, one
 * summary with the count is shown. The Lua library finds file, line, function and "via" (see core.lua); this class
 * only formats and sends. Main thread only.
 */
public final class ErrorReporter {

    private record Key(String kind, String file, int line, String message) {
    }

    private static final class Window {
        final long until;
        final String via;
        int repeats;

        Window(long until, String via) {
            this.until = until;
            this.via = via;
        }
    }

    private final ComponentLogger logger;
    private final Map<Key, Window> windows = new LinkedHashMap<>();
    private long windowNanos = 10_000_000_000L;
    private int errors; // errors since the last resetCount (shown in the reload summary)

    public ErrorReporter(ComponentLogger logger) {
        this.logger = logger;
    }

    public void setWindowSeconds(double seconds) {
        windowNanos = (long) (Math.max(0, seconds) * 1e9);
    }

    public void resetCount() {
        errors = 0;
    }

    public int count() {
        return errors;
    }

    /** kind: "error" or "warning"; file "" and line -1 when unknown; via may be "". */
    public void report(String kind, String file, int line, String via, String message) {
        if (kind.equals("error")) errors++;
        Key key = new Key(kind, file, line, message);
        long now = System.nanoTime();
        Window window = windows.get(key);
        if (window != null && now < window.until) {
            window.repeats++;
            return;
        }
        show(key, via, 0);
        windows.put(key, new Window(now + windowNanos, via));
    }

    /** Called every tick: shows the summary of each window that ended with repeats. */
    public void tick() {
        if (windows.isEmpty()) return;
        long now = System.nanoTime();
        Iterator<Map.Entry<Key, Window>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Key, Window> entry = it.next();
            Window window = entry.getValue();
            if (now < window.until) continue;
            if (window.repeats > 0) show(entry.getKey(), window.via, window.repeats);
            it.remove();
        }
    }

    private void show(Key key, String via, int repeats) {
        boolean warning = key.kind().equals("warning");
        // FORMAT: script error and warning format; console without symbols, see the class comment.
        StringBuilder head = new StringBuilder(warning ? "Warning" : "Error");
        if (!key.file().isEmpty()) {
            head.append(" in ").append(key.file());
            if (key.line() > 0) head.append(" on line ").append(key.line());
        }
        StringBuilder details = new StringBuilder();
        if (!via.isEmpty()) details.append("\n  via ").append(via);
        details.append("\n  ").append(key.message());
        if (repeats > 0) details.append("\n  ").append(repeats).append(repeats == 1 ? " time in " : " times in ").append(Math.round(windowNanos / 1e9)).append('s');

        Component chat = Component.text("⚠ " + head, warning ? NamedTextColor.YELLOW : NamedTextColor.RED)
                .append(Component.text(details.toString(), NamedTextColor.GRAY));
        Nms.sendToOps(chat);

        Component console = Component.text(head.toString(), warning ? null : NamedTextColor.RED)
                .append(Component.text(details.toString().replace("→", "->")));
        if (warning) logger.warn(console);
        else logger.error(console);
    }
}
