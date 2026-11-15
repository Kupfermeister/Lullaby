# How Lullaby is built

For people and AIs who want to change Lullaby itself. What scripts can use is on the
[docs site](https://kupfermeister.github.io/Lullaby/), not here; what Lullaby is: [README.md](README.md).

**Keeping this page true.** A change that moves a file, adds a part or changes a recipe updates this page in the
same change. Files are named here as links; the docs build ([Docs.java](tools/docs/Docs.java)) checks that every
linked file exists and stops with a clear message if one is missing.

## In one picture

Lullaby is a Paper plugin. Scripts are Lua, run by LuaJIT (a fast Lua 5.1) through the LuaJava library. Most of
Lullaby itself is a Lua library; Java does only what Lua can't (touch the game):

```
script            give{ player = p, ... }           plugins/lullaby/scripts/*.lua
  │
library (Lua)     checks what only Lua can see,     src/main/resources/lullaby/lib/*.lua
  │               packs the arguments ONCE
  │  J.name(...)  one call from Lua into Java
Java functions    JavaFunctions.java (table J)
  │
nms               the only door to Minecraft's code: vanilla packets, codecs, Brigadier
```

Events go the other way: Paper fires an event, `EventBridge` hands Lua two whole numbers (which listener, which
event), and the library runs the script handlers. Fields are read by number, never by name.

## Parts

Java, under `src/main/java/io/github/kupfermeister/lullaby/`:

| File | What it does |
|---|---|
| [Lullaby.java](src/main/java/io/github/kupfermeister/lullaby/Lullaby.java) | Plugin entry: the version lock, the per-tick job, `/lullaby` registration. |
| [Engine.java](src/main/java/io/github/kupfermeister/lullaby/Engine.java) | One load of the scripts: Lua state, listeners, files in `plugins/lullaby`. A reload throws it all away and builds it new. |
| [LullabyCommand.java](src/main/java/io/github/kupfermeister/lullaby/LullabyCommand.java) | `/lullaby reload` and `/lullaby version`, the one built-in command. In Java so it works when every script is broken. |
| [lua/LuaRuntime.java](src/main/java/io/github/kupfermeister/lullaby/lua/LuaRuntime.java) | The LuaJIT state; loads the library files in a fixed order (`LIBRARY`). |
| [lua/JavaFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaFunctions.java) | The raw Java functions the library calls (table `J`). Scripts never see them. |
| [lua/Wire.java](src/main/java/io/github/kupfermeister/lullaby/lua/Wire.java) | Lua tables as bytes in LuaJIT's own `string.buffer` format, so a whole table crosses in one call. |
| [lua/Slots.java](src/main/java/io/github/kupfermeister/lullaby/lua/Slots.java) | Lua objects carry a slot number; this holds what the number stands for (a UUID, a world, an event). |
| [lua/LuaText.java](src/main/java/io/github/kupfermeister/lullaby/lua/LuaText.java) | Text as real UTF-8 (LuaJava's own string conversion breaks emoji). |
| [lua/JavaDoor.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaDoor.java) | The raw Java door (`unlock.java`): loads classes, picks the method that fits the arguments, turns Java values into Lua ones. Made only when unlocked. |
| [lua/Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java) | `script_time_limit`: stops scripts that run too long. Runs a thread (see Threads). |
| [event/EventBridge.java](src/main/java/io/github/kupfermeister/lullaby/event/EventBridge.java) | The hand-picked Paper events and their fields (the one list, `EVENTS`). |
| [error/ErrorReporter.java](src/main/java/io/github/kupfermeister/lullaby/error/ErrorReporter.java) | Error and warning format, repeat collapsing, console and ops. |
| [nms/](src/main/java/io/github/kupfermeister/lullaby/nms/package-info.java) | Everything that touches Minecraft's own code, see below. |

The `nms` package ("net.minecraft server", Minecraft's own code) is the **only** package that may use
`net.minecraft.*`, CraftBukkit or Paper internals (`io.papermc.paper.adventure`). So a Minecraft update only needs
changes there. The raw Java door lets scripts reach Minecraft's classes by name at runtime; Lullaby's own code
never goes through it, so this rule stays true for Lullaby itself. Names are Mojang's own, which Paper uses at runtime, so nothing is remapped.

| File in `nms/` | What it does |
|---|---|
| [Packets.java](src/main/java/io/github/kupfermeister/lullaby/nms/Packets.java) | `particle{}`, `sound{}`, `stop_sound{}` on vanilla packets, and the one send core: build each packet once, bundle by 4096, share across receivers. |
| [Fields.java](src/main/java/io/github/kupfermeister/lullaby/nms/Fields.java) | The one place that reads and checks an action's fields, and the one wording of their errors. |
| [Codecs.java](src/main/java/io/github/kupfermeister/lullaby/nms/Codecs.java) | Item stacks, component patches and text through vanilla's codecs; owns the wire's kind list. |
| [LuaOps.java](src/main/java/io/github/kupfermeister/lullaby/nms/LuaOps.java) | Vanilla's `DynamicOps` over Lua data, so every vanilla codec reads Lua tables; refuses 2.5 where a whole number is needed. |
| [ScriptCommands.java](src/main/java/io/github/kupfermeister/lullaby/nms/ScriptCommands.java) | Script commands on vanilla's Brigadier dispatcher, with per-player snapshots (see Threads). |
| [Nms.java](src/main/java/io/github/kupfermeister/lullaby/nms/Nms.java) | Small vanilla reads and actions without a bigger class yet. |

The library, in `src/main/resources/lullaby/lib/` (loaded in this order; each file starts with a comment saying
what it holds and gets three tables: `J` Java functions, `lib` what scripts see as globals, `I` internal):

| File | What it holds |
|---|---|
| [core.lua](src/main/resources/lullaby/lib/core.lua) | Error reports, config, the sandbox (what is locked), `type_of`. |
| [values.lua](src/main/resources/lullaby/lib/values.lua) | Positions, rotations, boxes, location. Pure Lua, never calls Java. |
| [tasks.lua](src/main/resources/lullaby/lib/tasks.lua) | `run`, `wait`, `stop` and the scheduler that resumes waiting tasks (coroutines) each tick. |
| [events.lua](src/main/resources/lullaby/lib/events.lua) | `on`, `after`, event objects. |
| [objects.lua](src/main/resources/lullaby/lib/objects.lua) | Player, world, block, block state; `message`, `particle{}`, `sound{}`, `stop_sound{}`. |
| [lazy.lua](src/main/resources/lullaby/lib/lazy.lua) | The wire, item stacks, components, `from_json`, Lullaby's own `pairs`/`next` (LuaJIT ignores `__pairs`). |
| [commands.lua](src/main/resources/lullaby/lib/commands.lua) | `command{}`, `refresh_commands{}`, `online_players()`. |
| [loader.lua](src/main/resources/lullaby/lib/loader.lua) | Loading script files, `import`. |
| [java.lua](src/main/resources/lullaby/lib/java.lua) | The raw Java door's Lua side (table `java`, only when `unlock.java` is on) and its docs page. |

Other files:

- [defaults/](src/main/resources/defaults/config.lua): `config.lua` and `lullaby.lua`, copied to `plugins/lullaby` on
  the first start.
- [paper-plugin.yml](src/main/resources/paper-plugin.yml): the plugin descriptor (Paper's newer kind; its bootstrap
  step before worlds load is kept free for registry content later).
- [tools/docs/](tools/docs/Docs.java): the docs site builder (`Docs.java`, `site.html`, the Lua pages in
  [lua.txt](tools/docs/lua.txt)). Not part of the plugin.
- [types.lua](tools/docs/design/types.lua): the agreed list of every value type, a design page, not loaded.
- [build.gradle.kts](build.gradle.kts) and [gradle.properties](gradle.properties): the build; `minecraftVersion`
  there is the single place for the version.
- LuaJava and LuaJIT (Windows, Linux, macOS) are packed into the plugin jar, not relocated: each Paper plugin has its
  own class loader, so another plugin's copy can't clash.

## Threads (hard rules)

1. The Lua state, and every LuaJava object from it, is touched only on the server main thread. LuaJava is not
   thread-safe; calling into Lua from any other thread is a bug.
2. Bukkit and Minecraft world objects are touched only on the main thread.
3. Worker threads only work on copies they own and hand results back to the main thread through a queue. They never
   call Lua or touch live world objects. *(Planned users: big block edits on copies of chunk sections, big file
   copies.)*
4. Every class that runs work off the main thread says so in its Javadoc.

Today two places meet other threads:

- [Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java) runs one thread that only compares
  two numbers and raises a flag; the main thread checks that flag at every library call into Java.
- Paper builds each player's command tree on its own threads, so in
  [ScriptCommands.java](src/main/java/io/github/kupfermeister/lullaby/nms/ScriptCommands.java) Brigadier's `requires`
  checks and suggestions never call Lua: they read a per-player snapshot that Lua computes on the main thread (on
  join, on reload, on `refresh_commands{}`).

The raw Java door ([JavaDoor.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaDoor.java)) takes no Lua
functions, so Java can never call a script back and Lullaby never hands a raw object to another thread. A script can
still start a thread through the door itself; the docs page says plainly that this is not safe.

## Fundamentals

These hold everywhere. A change that breaks one is a design change, not a fix.

**Vanilla before Paper.** Packets use vanilla's packet ids (the same as the protocol wiki) plus direction and phase;
fields use Mojang's own names, written snake_case. Commands are vanilla Brigadier nodes. Text is vanilla text
components; messages go out as vanilla's System Chat Message packet. As few dependencies as possible.

**Script style.**
- One Lua state for all scripts, on the main thread.
- `{ }` is doing, `( )`, `.` and `:` are reading: `give{ player = p, item = "x" }` acts, `p.location:above(2)` reads.
- No hidden context: the player is always passed in (`message{ player = p, ... }`).
- Strings are literal text, also strings inside tables, exactly as vanilla's codecs read them. A component is a
  plain table in codec shape.
- Game objects (player, world, block, ...) exist once each, so `==` works. Reading functions turn text into objects
  (`player("Notch")`, `world("overworld")`) and give nil if there is none.
- Event fields come from Paper's getters and setters by one rule (`getJoinMessage()` → `e.join_message`,
  `isCancelled()` → `e.cancelled`), writable in `on`, read only in `after`.

**Speed.** A library function with named arguments is a thin Lua wrapper that reads the `{ }` fields and calls Java
once with plain values; Java never reads a Lua table field by field (each read is a slow Lua-Java trip). Several
fields go as one wire table. An event no script uses has no listener, so it costs nothing. Every speed claim names
its measurement; the one so far: an event field read went from 2.9 to 1.3 microseconds (200000 reads, `os.clock`,
test server) when fields moved from names to numbers.

**Checks.** Every value from a script is checked exactly once, where it becomes a game thing. Lua checks only what
Java can't see (is it a function, a player object, was `{ }` given); Java checks everything that arrives over the
wire, through [Fields.java](src/main/java/io/github/kupfermeister/lullaby/nms/Fields.java). Errors name the field,
what it needs and what it got: `count must be a whole number, got 2.5`.

**One owner per list.** Java owns the game's facts (wire kinds, events and their fields, event stages, sound
sources); the library reads them once at startup. Doc blocks own what a script function takes. When Java's list and
Lua's disagree, loading stops with a message starting `Lullaby bug:`. Nothing looks things up by a position number.

**The engine.**
- Version lock: it runs only on the exact Minecraft version it was built for. No compatibility layers: what is
  replaced is removed.
- Dangerous Lua parts (`io`, `os`, `ffi`, `debug`, C modules) and the raw Java door are locked by default and unlocked in the config
  (README lists what each allows). Deliberate power needs an unlock; one script must not break another by accident.
- Error format: `⚠ Error in <path> on line N` in red (warnings yellow, `Warning`), then gray lines: `via <event> →
  <calls>`, the Lua message without its `file:line:` prefix, and `N times in 10s` for collapsed repeats. The console
  gets no symbols (`->` instead of `→`, no `⚠`), because Windows consoles show them as garbage; console colors go
  through Paper's component logger, log files stay plain. Details:
  [ErrorReporter.java](src/main/java/io/github/kupfermeister/lullaby/error/ErrorReporter.java).
- Every text players or the console see has a `// FORMAT:` comment (in Lua `-- FORMAT:`) right above it, so all of
  them can be found and polished: search for `FORMAT:`.
- Endless loops never kill the server and never stop a script silently: see
  [Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java), including what it can't stop.

**Docs.** Everything scripts can use has a `--[[doc ... ]]` block right above its `function lib.NAME(`, in the same
change that adds or changes it. The format is at the top of [Docs.java](tools/docs/Docs.java). The block feeds the
docs site, VS Code autocomplete and the Markdown copy; uses and used-by are found from the code, not written.

## Build and test

- `gradlew build` builds `build/libs/lullaby-<version>.jar` and copies it to `run/plugins` (older Lullaby jars there
  are removed). The first build takes a few minutes: paperweight sets up the Minecraft sources once.
- `gradlew runServer` builds, downloads the Paper jar named in `gradle.properties` once (SHA-256 checked) and starts a
  test server in `run/` (not in the repo): port 25566, 4 GB. Minecraft's EULA must be accepted in `run/eula.txt`
  first. Type `stop` to end it.
- `gradlew docs` builds the docs site into `build/docs`. GitHub runs the same on every push to `main` and publishes
  it ([docs.yml](.github/workflows/docs.yml)).

## Recipes

Each recipe names the files in order. What it says follows by itself really does: it comes from startup checks or
the docs build, not from discipline.

### Add a library function

1. Pick the library file by topic (table above). Write `function lib.NAME(...)` with a `--[[doc` block right
   above it. Doing functions take `{ }` (`function lib.NAME(a)`, fields `a.player`, ...); reading functions take
   plain arguments.
2. If plain Lua can do it, stop here.
3. Otherwise, in the Lua function check only what Java can't see (`{ }` given, player objects), then call Java once:
   `J.name(plain values)`, or `J.name(I.wire({ ... }))` for several fields.
4. In [JavaFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaFunctions.java), inside `fill`,
   add `add(lua, "name", L -> { ... })`. Arguments are stack positions 1, 2, ...; push the results and return how
   many. Text goes through `LuaText`. Use `add`, not `addFree` (`add` checks the script time limit; `addFree` is only
   for what the library needs while loading or reporting).
5. Anything that touches Minecraft's code goes into a static method in `nms` ([Nms.java](src/main/java/io/github/kupfermeister/lullaby/nms/Nms.java)
   or the class of its topic). Wire fields are read through `Fields`. Every new message text gets a `FORMAT:`
   comment.

Follows by itself: scripts see `lib.NAME` as a global; the docs site page, autocomplete and the Markdown copy come
from the doc block; "uses" and the grayed page for `J.name` are found from the code.

### Add an event

1. In [EventBridge.java](src/main/java/io/github/kupfermeister/lullaby/event/EventBridge.java), import the Paper
   event class and add one line to `EVENTS`: the event's class name, its class, and its fields in snake_case by the
   naming rule (`getCommand()` → `command`, `isCancelled()` → `cancelled`).
2. If a field's Java type is new (known today: player, block, location, text component, enum, true/false, number,
   string), add it to `get` (and `set`, if writable) in the same file and to `from_java` in
   [events.lua](src/main/resources/lullaby/lib/events.lua). Without this the field reads as nil.
3. In [events.lua](src/main/resources/lullaby/lib/events.lua), add the event and its fields to the doc block
   `id: events` (the docs builder can't read Java yet, so this list is still written by hand).

Follows by itself: the library learns the event and its fields at startup; the "unknown event" error lists it; a
field name Paper doesn't have stops loading with `Lullaby bug: <event> has no getter for <field>`; until a script
uses the event, no listener exists.

### Add a packet action

1. In [Packets.java](src/main/java/io/github/kupfermeister/lullaby/nms/Packets.java), add
   `public static void name(Object wire, IntFunction<Object> slots)`: `Fields a = new Fields("name{}", wire)`, read
   each field with vanilla's own default, build the vanilla packet(s), then `send(receivers(a, slots), packets)`.
   Field names are Mojang's, snake_case.
2. In [JavaFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaFunctions.java), add
   `add(lua, "name", L -> { Packets.name(Wire.read(L.toBuffer(1)), slotLookup); return 0; });`.
3. In [objects.lua](src/main/resources/lullaby/lib/objects.lua), add the doc block (with
   `expert: Packet | <the packet's protocol wiki name> (play, clientbound)`) and
   `function lib.name(a) action_table(a, "name"); J.name(I.wire({ players = receivers(a, "name"), ... })) end`.

Follows by itself: the player/players check and its error, every field error's wording (from `Fields`), bundling
when there is more than one packet, building each packet once for all receivers, the docs page.

### Update to a new Minecraft version

1. In [gradle.properties](gradle.properties) set `minecraftVersion`, `paperBuild` and `paperJarSha256` (the Paper
   build and its SHA-256 are on Paper's downloads page).
2. `gradlew build`. The first build sets up the new Minecraft sources (a few minutes).
3. Fix what no longer compiles. It should all be in `nms`; anything outside is a change in Paper's own API.
4. `gradlew runServer`. The startup checks stop loading with `Lullaby bug: ...` if vanilla renamed something Lullaby
   reads (event getters and setters, wire kinds). Fix those in `nms` or `EventBridge`.
5. Sounds: a fixed `seed` in `sound{}` picks the variant from the sound's pool in vanilla's `sounds.json`. If Mojang
   changed a pool, the same seed now gives another variant; check the sounds whose exact variant matters.
6. `gradlew docs`: the site shows the new version (read from `gradle.properties`).

Follows by itself: the plugin's `api-version` and the version lock use the new version; old jars refuse the new
server and say which build is needed.

## Plans (not built)

Marked so nobody mistakes them for features: events generated from Paper's own methods instead of the hand-picked list; script data that lives in Java and
survives reloads; a generated packet system (`on_packet`, `send_packet`) on the same send core; entities and
inventories; worker threads for big block edits; mixins as an optional add-on.
