# Lullaby

Lua scripting for Paper servers. You write small `.lua` files, Lullaby runs them inside the server: react to
events, add commands, send messages, particles and sounds. It is for server owners who want their own game rules
without writing a Java plugin, and for experts who want close-to-vanilla control without the slowness that usually
comes with scripting.

```lua
-- plugins/lullaby/scripts/hello.lua
command{ "hello", function(c)
  message{ player = c.player, text = "Hello, " .. c.player.name .. "!" }
end }
```

**Does it fit your server?**

- **Not released yet.** There are no downloads; you build the jar yourself (below). Things still change.
- **Paper 26.3 only.** Lullaby runs only on the exact Minecraft version it was built for, and stays off on any other
  (it says so in the console). The version is `minecraftVersion` in [gradle.properties](gradle.properties).
- **Java 25.**

## Docs

Everything scripts can use (a beginner guide first, then every function, event and field) is on the docs site:
**https://kupfermeister.github.io/Lullaby/**. It is built from the code on every change, so it is never older than
the code. The same text also comes as `lullaby.d.lua` for autocomplete in VS Code (Lua extension) and as
`lullaby.md`, both made by `gradlew docs` in `build/docs`.

How the plugin is built inside, and how to add to it: [ARCHITECTURE.md](ARCHITECTURE.md).

## First script

1. Build the jar: `gradlew build` (the first build takes a few minutes). It lands in `build/libs/`.
2. Put it in your Paper 26.3 server's `plugins` folder and start the server once. Lullaby creates
   `plugins/lullaby/` with `config.lua` and `scripts/lullaby.lua` (examples, all switched off).
3. Open `plugins/lullaby/scripts/lullaby.lua` and add the `hello` command from above at the end.
4. In the game (as op) or in the console, type `/lullaby reload` (or the short `/lul`).
5. Type `/hello`. You get the greeting.

A mistake shows up as a red message with the file and line, in the console and for ops in chat. Every `.lua` file
in `scripts/` and its folders loads; a file whose name starts with `--` is skipped. Next stop:
the [beginner guide](https://kupfermeister.github.io/Lullaby/#guide) on the docs site.

## Locked by default

All scripts share one Lua state on the server's main thread, so a script can do whatever its Lua allows. Lua's
dangerous parts are therefore switched off. Each can be unlocked in `plugins/lullaby/config.lua`; unlock one only
when a script you trust needs it, because it then works for every script on the server.

| Unlock | What scripts can then do | Why it is locked |
|---|---|---|
| `io` | read and write any file the server can reach (also `dofile`, `loadfile`) | could read secrets or delete worlds |
| `os` | run programs, delete files, read the clock | could take over the machine |
| `ffi` | call any native code directly | can crash the server or do anything at all |
| `debug` | look into and change other functions, also Lullaby's own; `jit`, `collectgarbage`, `getfenv`, `setfenv`, `gcinfo`, `newproxy` | one script could break every other script and the library |
| `modules` | `require`, `module` and native (C) modules | loads code from outside `scripts/`; scripts use `import` instead |
| `java` | use any Java directly through the table `java`: Paper's and Bukkit's API, Minecraft's own classes, other plugins (the docs page "the Java door") | can do anything at all, like `io`, `os` and `ffi` together, and raw code breaks on Minecraft updates |

LuaJava's own `java` table (a way to reach any Java class) is always removed; `unlock.java` gives Lullaby's own
instead, with Lullaby's error messages and the script time limit.

## Honest limits

- A script that runs longer than `script_time_limit` (config, 5 seconds by default) gets a red error at its next
  call into the game and is stopped. A loop that never calls into the game (pure math, `while true do end`) can't be
  stopped this way; the console then names what is stuck.
- Speed is measured, not promised: reading an event field (`e.player`) costs about 1.3 microseconds (200000 reads,
  measured with `os.clock` on the test server).

## License

MIT, see [LICENSE](LICENSE).
