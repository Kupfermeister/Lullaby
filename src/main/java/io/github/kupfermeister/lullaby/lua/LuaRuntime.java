package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
import io.github.kupfermeister.lullaby.event.EventBridge;
import io.github.kupfermeister.lullaby.nms.Input;
import io.github.kupfermeister.lullaby.nms.SavedData;
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

    private static final String[] LIBRARY = {"core", "values", "tasks", "events", "objects", "lazy", "commands", "data", "worlds",
            "loader", "java"};

    private final Lua lua;
    private final Slots slots = new Slots();
    private final Watchdog watchdog;
    private final EventBridge events;
    private final int internals; // registry reference to the I table
    private final int dispatch;  // registry reference to I.dispatch (called for every event: no lookup by name)
    private final int dispatchInput; // the same for I.dispatch_input (Lullaby's own input event)

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
        pushInternal("dispatch_input");
        dispatchInput = lua.ref();
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

    // ---- server.data (data.lua, nms/SavedData.java) ----

    /** Fills server.data from the file; called before the scripts load. */
    public void loadData(SavedData data) {
        int top = lua.getTop();
        try {
            ByteBuffer bytes = data.read(slots::add);
            pushInternal("data_load");
            if (bytes == null) lua.pushNil();
            else lua.push(bytes);
            enterLua("loading server.data", 1, 1);
            data.loaded(LuaText.string(lua, -1));
        } catch (RuntimeException e) {
            data.loadFailed(); // never save a half-loaded server.data over the file
            throw e;
        } finally {
            lua.setTop(top);
        }
    }

    /** Saves server.data; {@code wait}: write the file now instead of on the writer thread. */
    public void saveData(SavedData data, boolean wait) {
        int top = lua.getTop();
        try {
            pushInternal("data_save");
            enterLua("saving server.data", 0, 2);
            Object tree = Wire.read(lua.toBuffer(-2));
            data.saveProblems(LuaText.string(lua, -1));
            data.save(tree, slots::get, wait);
        } finally {
            lua.setTop(top);
        }
    }

    /** /lullaby data server [path]: the view of one place ({tree}, or null if nothing is there) and its name. */
    public Object[] dataView(String path) {
        int top = lua.getTop();
        try {
            pushInternal("data_view");
            LuaText.push(lua, path);
            enterLua("showing server.data", 1, 2);
            Object tree = lua.isNil(-2) ? null : Wire.read(lua.toBuffer(-2));
            return new Object[]{tree == null ? null : SavedData.view(tree, slots::get), LuaText.string(lua, -1)};
        } finally {
            lua.setTop(top);
        }
    }

    /** The keys at one place of server.data, sorted (tab completion). */
    public List<String> dataKeys(String path) {
        int top = lua.getTop();
        try {
            pushInternal("data_keys");
            LuaText.push(lua, path);
            enterLua("listing server.data keys", 1, 1);
            String keys = LuaText.string(lua, -1);
            return keys.isEmpty() ? List.of() : List.of(keys.split("\n"));
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

    /**
     * Runs the input event's on (or after) handlers for one packet; returns true if an on handler cancelled it. Lua
     * gets: after, the packet's number, the event's slot, the player's UUID and why it can't be cancelled (or nil).
     */
    public boolean dispatchInput(boolean after, Input.Event event) {
        int top = lua.getTop();
        try {
            lua.refGet(dispatchInput);
            lua.push(after);
            lua.push(event.kind());
            lua.push(slots.add(event));
            lua.push(event.player().toString());
            String refusal = event.refusal();
            if (refusal == null) lua.pushNil();
            else LuaText.push(lua, refusal);
            enterLua(after ? "the input event (after)" : "the input event (on)", 5, 1);
            return lua.toBoolean(-1);
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
