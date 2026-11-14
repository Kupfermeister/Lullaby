package io.github.kupfermeister.lullaby.lua;

import io.github.kupfermeister.lullaby.Engine;
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

    private static final String[] LIBRARY = {"core", "tasks", "events", "objects", "commands", "loader"};

    private final Lua lua;
    private final int internals; // registry reference to the I table

    public LuaRuntime(Engine engine) throws IOException {
        lua = new LuaJit();
        lua.openLibraries();
        lua.newTable();
        JavaFunctions.fill(lua, engine);
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
        call("setup");
    }

    /** Loads every script; returns how many files loaded. */
    public int loadAll() {
        int top = lua.getTop();
        try {
            pushInternal("load_all");
            lua.pCall(0, 1);
            return (int) lua.toInteger(-1);
        } finally {
            lua.setTop(top);
        }
    }

    public void tick() {
        call("tick");
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
            lua.pCall(4, 0);
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
            lua.pCall(1, 0);
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
            lua.pCall(2 + args.size(), 0);
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
        call("stop_all_tasks");
    }

    /** Runs the script handlers for one event. The event object stays private to the library. */
    public void dispatch(String name, String kind, String stage, Object event) {
        int top = lua.getTop();
        try {
            pushInternal("dispatch");
            lua.push(name);
            lua.push(kind);
            lua.push(stage);
            lua.pushJavaObject(event);
            lua.pCall(4, 0);
        } finally {
            lua.setTop(top);
        }
    }

    public void close() {
        lua.close();
    }

    private void call(String name) {
        int top = lua.getTop();
        try {
            pushInternal(name);
            lua.pCall(0, 0);
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
