package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import party.iroiro.luajava.Lua;
import party.iroiro.luajava.luajit.LuaJit;

/**
 * One Lua state (LuaJIT through LuaJava) with the Lullaby library loaded. A reload throws the whole state away and
 * builds a new one. Main thread only: LuaJava is not thread-safe, and nothing here may be called from other threads.
 *
 * <p>The library itself is Lua (resources {@code lullaby/lib/*.lua}, loaded in the order of {@link #LIBRARY}); each
 * file gets the tables J (Java functions, see {@link JavaFunctions}), lib (what scripts see) and I (internal
 * functions). Java calls into the library only through the I table, by name ({@link #pushInternal}).
 */
public final class LuaRuntime {

    private static final String[] LIBRARY = {"core", "values", "tasks", "events", "objects", "lazy", "commands", "loader"};

    private final Lua lua;
    private final Slots slots = new Slots();
    private final Watchdog watchdog;
    private final EventBridge events;
    private final int internals; // registry reference to the I table
    private final int dispatch;  // registry reference to I.dispatch (called for every event: no lookup by name)

    public LuaRuntime(Engine engine) throws IOException {
        watchdog = engine.watchdog();
        events = engine.events();
        lua = new LuaJit();
        lua.openLibraries();
        lua.newTable();
        JavaFunctions.fill(lua, engine, slots);
        int javaFunctions = lua.ref();
        lua.newTable();
        int library = lua.ref();
        lua.newTable();
        internals = lua.ref();
        for (String name : LIBRARY) {
            lua.load(resource("/lullaby/lib/" + name + ".lua"), "@lullaby/" + name + ".lua");
            lua.refGet(javaFunctions);
            lua.refGet(library);
            lua.refGet(internals);
            lua.pCall(3, 0);
        }
        // The library keeps J and lib as upvalues; the registry references are no longer needed.
        lua.unref(javaFunctions);
        lua.unref(library);
        call("setup", "setting up the library");
        pushInternal("dispatch");
        dispatch = lua.ref();
    }

    /** Loads every script; returns how many files loaded. */
    public int loadAll() {
        int top = lua.getTop();
        try {
            pushInternal("load_all");
            enterLua("loading the scripts", 0, 1);
            return (int) lua.toInteger(-1);
        } finally {
            lua.setTop(top);
        }
    }

    public void tick() {
        call("tick", "the waiting tasks (a tick)");
    }

    /** The reload event; {@code player} is who reloaded (null: the console). */
    public void fireReload(int scripts, int errors, long millis, UUID player) {
        int top = lua.getTop();
        try {
            pushInternal("fire_reload");
            lua.push(scripts);
            lua.push(errors);
            lua.push(millis);
            pushUuid(player);
            enterLua("the reload event", 4, 0);
        } finally {
            lua.setTop(top);
        }
    }

    /** Works out which script commands one online player sees and resends their command tree. */
    public void refreshCommands(String uuid) {
        int top = lua.getTop();
        try {
            pushInternal("refresh_player");
            lua.push(uuid);
            enterLua("working out a player's commands", 1, 0);
        } finally {
            lua.setTop(top);
        }
    }

    /** A script command was typed: its node id, who typed it (null: console), then name, kind, value triples. */
    public void runCommand(int node, UUID player, List<Object> args) {
        int top = lua.getTop();
        try {
            pushInternal("run_command");
            lua.push(node);
            pushUuid(player);
            for (Object value : args) {
                switch (value) {
                    case String text -> LuaText.push(lua, text);
                    case Boolean bool -> lua.push(bool);
                    case Number number -> lua.push(number);
                    default -> throw new IllegalArgumentException("unexpected command value " + value);
                }
            }
            enterLua("a script command", 2 + args.size(), 0);
        } finally {
            lua.setTop(top);
        }
    }

    private void pushUuid(UUID uuid) {
        if (uuid == null) lua.pushNil();
        else lua.push(uuid.toString());
    }

    /** Runs every running task's final; called before the state is closed. */
    public void stopAllTasks() {
        call("stop_all_tasks", "the finals of the running tasks");
    }

    /**
     * Runs the script handlers for one event. Lua gets two whole numbers: the listener's number (from
     * EventBridge.listen) and the event's slot (see {@link Slots}); no text crosses.
     */
    public void dispatch(int listener, Object event) {
        int top = lua.getTop();
        try {
            lua.refGet(dispatch);
            lua.push(listener);
            lua.push(slots.add(event));
            enterLua(events.listenerName(listener), 2, 0);
        } finally {
            lua.setTop(top);
        }
    }

    /** Every call from Java into Lua (after loading the library) goes through here, so the watchdog sees it. */
    private void enterLua(String what, int arguments, int results) {
        watchdog.enter(what); // what: for the console note if Lua gets stuck
        try {
            lua.pCall(arguments, results);
        } finally {
            watchdog.exit();
        }
    }

    public void close() {
        lua.close();
    }

    private void call(String name, String what) {
        int top = lua.getTop();
        try {
            pushInternal(name);
            enterLua(what, 0, 0);
        } finally {
            lua.setTop(top);
        }
    }

    private void pushInternal(String name) {
        lua.refGet(internals);
        lua.getField(-1, name);
        lua.remove(-2);
    }

    /** A library file as a direct buffer (LuaJava loads code only from direct buffers or strings). */
    private static ByteBuffer resource(String path) throws IOException {
        try (InputStream in = LuaRuntime.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing library file " + path);
            byte[] bytes = in.readAllBytes();
            return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
        }
    }
}
