package io.github.kupfermeister.lullaby.lua;

/**
 * Stops scripts that run too long (the config's script_time_limit), without costing anything while scripts behave.
 *
 * <p>RUNS A THREAD: one daemon thread ("Lullaby watchdog") wakes 10 times per second and only compares two numbers.
 * It never touches Lua or the world; all it does is raise a flag ({@link #expired}). The main thread checks that
 * flag at every library call into Java (JavaFunctions.add): a script that runs too long gets a normal red error at
 * its next call into the game, its task ends and its final runs. The clock then starts again, so the other scripts
 * in the same tick keep working.
 *
 * <p>Honest limit: a loop that never calls into the game ({@code while true do end}, pure math) can't be stopped
 * this way, because LuaJIT's compiled loops ignore every outside signal. After twice the limit the thread writes one
 * console note naming what is stuck (the event, tick, command, ...); Paper's own watchdog handles a frozen server.
 *
 * <p>The clock runs per entry from Java into Lua (an event, a tick, a command, ...), and per script file while
 * loading. Nested entries (Lua -> Java -> Lua) count as one.
 */
public final class Watchdog {

    private volatile boolean expired;
    private volatile long started;  // System.nanoTime() when the current entry began
    private volatile boolean running;
    private volatile long limit;    // nanoseconds; 0: no limit
    private int depth;              // main thread only
    private volatile String what = ""; // what Java called into Lua for ("PlayerMoveEvent (on)", ...)
    private volatile boolean reported;
    private String message = "";
    private final org.slf4j.Logger log;
    private final Thread thread;

    public Watchdog(org.slf4j.Logger log) {
        this.log = log;
        thread = new Thread(this::watch, "Lullaby watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(100);
                long max = limit;
                if (max <= 0 || !running) continue;
                long ran = System.nanoTime() - started;
                if (!expired && ran > max) expired = true;
                // Twice the limit and still no call into the game: a loop that can't be stopped. Only a note in the
                // console (logging is safe from any thread); the thread never touches Lua or the world.
                if (!reported && ran > 2 * max) {
                    reported = true;
                    // FORMAT: the main thread is stuck in a script that can't be stopped (console only, no symbols)
                    log.warn("A script has been running for {} s without a break, in {}, and makes no call into the "
                            + "game, so Lullaby can't stop it (a loop of pure math?). The server is frozen until it "
                            + "ends; Paper's own watchdog steps in if it never does.", numberText(ran / 1e9), what);
                }
            }
        } catch (InterruptedException e) {
            // plugin disabled
        }
    }

    /** Ends the thread (plugin disable). */
    public void shutdown() {
        thread.interrupt();
    }

    /**
     * Sets the limit from the config (seconds; 0 or less: none) and returns the exact error text, so the library
     * can recognise it and let it through pcall.
     */
    public String setLimit(double seconds) {
        limit = seconds > 0 ? (long) (seconds * 1e9) : 0;
        // FORMAT: a script stopped by script_time_limit
        message = "ran longer than " + numberText(seconds) + " s (the config's script_time_limit) and was stopped";
        return message;
    }

    /** True once the current entry ran past the limit. Read by the main thread at every library call. */
    public boolean expired() {
        return expired;
    }

    /** The error text for the script that is stopped; starts the clock again for the code that runs next. */
    public String stop() {
        restart();
        return message;
    }

    /** Starts the clock again (after a stop, and for each script file while loading). */
    public void restart() {
        started = System.nanoTime();
        expired = false;
        reported = false;
    }

    /** Loading one script file: its own time, and the console note names the file. Main thread only. */
    public void loading(String path) {
        what = "loading scripts/" + path;
        restart();
    }

    /** Java is about to call into Lua for {@code what} (named in the console note). Main thread only. */
    public void enter(String what) {
        if (depth++ == 0) {
            this.what = what;
            restart();
            running = true;
        }
    }

    /** The call into Lua returned. Main thread only. */
    public void exit() {
        if (--depth == 0) {
            running = false;
            expired = false;
        }
    }

    private static String numberText(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? Long.toString((long) d) : String.format(java.util.Locale.ROOT, "%.1f", d);
    }
}
