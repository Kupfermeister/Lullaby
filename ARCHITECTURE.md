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
| [Engine.java](src/main/java/io/github/kupfermeister/lullaby/Engine.java) | One load of the scripts: Lua state, listeners, files in `plugins/lullaby`. A reload saves `server.data`, every `w.data`, `c.data` and `entity.data`, throws it all away and builds it new. It also decides when saved data is saved (world saves, world, chunk and entity unloads, player saves and quits). |
| [LullabyCommand.java](src/main/java/io/github/kupfermeister/lullaby/LullabyCommand.java) | `/lullaby reload`, `/lullaby version`, `/lullaby data server|world <world>|chunk [path]` (with tab completion of the keys; chunk: the one you stand in), `/lullaby run <code>` (one line of Lua with `me`, started as a task by loader.lua's `raw_run`) and `/lul` (exactly `/lullaby reload`), the built-in commands. In Java so it works when every script is broken. |
| [lua/LuaRuntime.java](src/main/java/io/github/kupfermeister/lullaby/lua/LuaRuntime.java) | The LuaJIT state; loads the library files in a fixed order (`LIBRARY`). |
| [lua/JavaFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaFunctions.java) | The raw Java functions the library calls (table `J`); scripts never see them. Holds `add` (every J function goes through it) and the functions of core.lua, values.lua, loader.lua and java.lua. Each other library file's functions are in the file of the same topic next to it: events.lua → [EventFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/EventFunctions.java), entities.lua → [EntityFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/EntityFunctions.java), blocks.lua → [BlockFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/BlockFunctions.java), effects.lua → [EffectFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/EffectFunctions.java), lazy.lua → [LazyFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/LazyFunctions.java), commands.lua → [CommandFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/CommandFunctions.java), data.lua → [DataFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/DataFunctions.java), worlds.lua → [WorldFunctions](src/main/java/io/github/kupfermeister/lullaby/lua/WorldFunctions.java). They stay out of `nms`, so `nms` never handles the Lua stack. |
| [lua/Wire.java](src/main/java/io/github/kupfermeister/lullaby/lua/Wire.java) | Lua tables as bytes in LuaJIT's own `string.buffer` format, so a whole table crosses in one call. |
| [lua/Slots.java](src/main/java/io/github/kupfermeister/lullaby/lua/Slots.java) | Lua objects carry a slot number; this holds what the number stands for (a UUID, a world, an event). |
| [lua/LuaText.java](src/main/java/io/github/kupfermeister/lullaby/lua/LuaText.java) | Text as real UTF-8 (LuaJava's own string conversion breaks emoji). |
| [lua/JavaDoor.java](src/main/java/io/github/kupfermeister/lullaby/lua/JavaDoor.java) | The raw Java door (`unlock.java`): loads classes, picks the method that fits the arguments, turns Java values into Lua ones. Made only when unlocked. |
| [lua/Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java) | `script_time_limit`: stops scripts that run too long. Runs a thread (see Threads). |
| [event/EventBridge.java](src/main/java/io/github/kupfermeister/lullaby/event/EventBridge.java) | Every Paper event: reads the generated list (`events.txt`, see Other files), looks an event's class and methods up the first time a script uses it, reads and sets its fields, keeps other threads away from Lua. |
| [lua/ToLua.java](src/main/java/io/github/kupfermeister/lullaby/lua/ToLua.java) | The one place that turns a Java value (player, entity, world, block, chunk, location, vector, box, block data, damage source, item stack, text, plain values) into a Lullaby value for Lua; event fields, entity fields and `java.wrap( )` use it. Its Lua half is `I.from_java` in entities.lua. |
| [error/ErrorReporter.java](src/main/java/io/github/kupfermeister/lullaby/error/ErrorReporter.java) | Error and warning format, repeat collapsing, console and ops. |
| [nms/](src/main/java/io/github/kupfermeister/lullaby/nms/package-info.java) | Everything that touches Minecraft's own code, see below. |
| [lullablend/LullaBlend.java](src/main/java/io/github/kupfermeister/lullaby/lullablend/LullaBlend.java) | LullaBlend, the server side of the Blender add-on (its own repository, Kupfermeister/LullaBlend): one session per player (token, origin, mode, viewers, last scene). A scene from Blender is built into a block edit on the block worker and shown as fake blocks; `place` writes it and keeps an undo file in `plugins/lullaby/lullablend/undo`. Tokens are saved only as SHA-256 in `plugins/lullaby/lullablend/tokens.txt`. Lives as long as the plugin. |
| [lullablend/LinkServer.java](src/main/java/io/github/kupfermeister/lullaby/lullablend/LinkServer.java) | The port Blender connects to (config `lullablend_port`, off by default) and the protocol, written out in its Javadoc (the add-on's link.py follows it). |
| [lullablend/LullaBlendCommand.java](src/main/java/io/github/kupfermeister/lullaby/lullablend/LullaBlendCommand.java) | `/lullaby blenderbridge token, origin, mode, show, hide, viewers, place, undo, status`, and `as <player> ...` for the console and admins (permission `lullaby.blenderbridge`). |
| [mosaic/](src/main/java/io/github/kupfermeister/lullaby/mosaic/package-info.java) | Sound mosaicing, `/lullaby mosaic play <file> [filters]` and `stop` (permission `lullaby.mosaic`): rebuilds an audio file from `plugins/lullaby/sound_mosaicing_sources` out of vanilla sound files (downloaded from Mojang by hash, analysed once), placed by a strict greedy solver in layers (melody, clean bass, main, quiet detail; sounds over 0.5 s cut off the moment they stop fitting) or, with `method = slices`, slice by slice, and plays it. Settings, tonality list, results and preview .wav files in `plugins/lullaby/sound_mosaicing`. Removable: package-info.java lists every line to delete. |

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
| [SoundSeeds.java](src/main/java/io/github/kupfermeister/lullaby/nms/SoundSeeds.java) | `sound_seed()` and `precise_sound{}`: runs the game's own file pick over vanilla's `sounds.json` (downloaded at build) to find the seed for one exact file. |
| [Entities.java](src/main/java/io/github/kupfermeister/lullaby/nms/Entities.java) | Entities: what an entity object's slot holds (its UUID and the vanilla entity last seen, found again by UUID after an unload or a world change), the entity field list and what `modify_entity{}` changes (their owner; also what a blueprint holds), `teleport{}`, `remove_entity{}`, `summon{}`, the check of `blueprint{}`, and the entities of a chunk. |
| [ClientEntities.java](src/main/java/io/github/kupfermeister/lullaby/nms/ClientEntities.java) | `clientside_summon{}`: entities only their viewers' games know. Each keeps a vanilla entity that is never put into a world (so the same code changes and reads it as a real one) and sends its viewers vanilla's spawn packets, then only what changed; lifetime, `persist` (sent again after a viewer's game forgot it: chunk, world, rejoin; its listeners exist only while one persists), removal at reload. |
| [DisplayData.java](src/main/java/io/github/kupfermeister/lullaby/nms/DisplayData.java) | `display_data`: the settings of text, item and block displays as one table, written (modify_entity, blueprints, summons) and read back in the same shape (owner of that shape). |
| [Input.java](src/main/java/io/github/kupfermeister/lullaby/nms/Input.java) | Lullaby's own `input` event: owns the input packets, their fields and action words (startup checks them against vanilla); a hook in each player's network pipeline, only while a script listens; what a cancelled packet gets back (vanilla's own refusal). |
| [SavedData.java](src/main/java/io/github/kupfermeister/lullaby/nms/SavedData.java) | One saved data file: `server.data` (`plugins/lullaby/data/server.snbt`) or one world's `w.data` (`data/lullaby.snbt` in the world's folder), through vanilla's SNBT printer and parser, codecs and data fixer; also the value conversion ChunkData uses. A hand edit wins, a file that didn't load cleanly is never saved over, past `data_max_size` nothing is saved. One per file, living as long as the plugin, so it outlasts reloads. |
| [ChunkData.java](src/main/java/io/github/kupfermeister/lullaby/nms/ChunkData.java) | `c.data` inside each chunk (one entry in the chunk's plugin data, saved by the server with the chunk): read when a script first touches it, written when the chunk unloads and when its world saves, only when changed. Remembers what it wrote for the tables of unloaded chunks, which Lua keeps until the next reload ("keep alive") and loads the chunk to write a later change. |
| [EntityData.java](src/main/java/io/github/kupfermeister/lullaby/nms/EntityData.java) | `entity.data` and `player.data` (the same field) inside each entity (one entry in its plugin data, saved by the server with the entity): a player's in the player file, also read and written there while the player is offline. Written when the entity's chunk unloads (then Lua locks the table) and at world saves; a player's right before Paper saves the player (its own timer, checked at the tick's start), at quit, reload and stop. An entity that dies takes its data with it. |
| [Worlds.java](src/main/java/io/github/kupfermeister/lullaby/nms/Worlds.java) | Worlds as folders: `worlds()`, `create_world{}`, `load_world{}`, `unload_world{}`, `copy_world{}`, `delete_world{}`. Creating and loading go through Paper's WorldCreator on purpose (Paper rewrites world loading, so vanilla's own classes would break with every update); vanilla's dimension folders, registries and seed rule. Copies are built in `plugins/lullaby/temp` and moved into place, so a crash never leaves half a world; a deleted world is moved there whole and kept as a last chance until the next start, which empties temp. Lives as long as the plugin, so a copy outlasts a reload. |
| [BlockEdit.java](src/main/java/io/github/kupfermeister/lullaby/nms/BlockEdit.java) | The block engine's unit of work: blocks of one world packed per chunk section (palette of vanilla state numbers, 4096 indices, 0 = untouched), built by a `Builder` on any one thread, unchangeable once built; also the undo file format. |
| [BlockEngine.java](src/main/java/io/github/kupfermeister/lullaby/nms/BlockEngine.java) | The block engine, for every big block job (LullaBlend now, a `fill{}` action later): **show** an edit as fake blocks to chosen players (a hook in their pipeline puts the fake blocks back into every chunk and block update the server sends), and **write** an edit straight into the chunk sections like FAWE (no block updates or physics; at most `block_write_ms` per tick; then Paper's light engine lights the chunks again and they are resent; gives back the old blocks as an undo edit). The block worker thread runs build jobs (`work`). Lives as long as the plugin. |
| [MosaicSender.java](src/main/java/io/github/kupfermeister/lullaby/nms/MosaicSender.java) | Sound mosaicing's packets: built on the main thread, sent by the playback thread. |
| [Nms.java](src/main/java/io/github/kupfermeister/lullaby/nms/Nms.java) | Small vanilla reads and actions without a bigger class yet. |

The library, in `src/main/resources/lullaby/lib/` (loaded in this order; each file starts with a comment saying
what it holds and gets three tables: `J` Java functions, `lib` what scripts see as globals, `I` internal):

| File | What it holds |
|---|---|
| [core.lua](src/main/resources/lullaby/lib/core.lua) | Error reports, config, the sandbox (what is locked), `type_of`. |
| [values.lua](src/main/resources/lullaby/lib/values.lua) | Positions, rotations, boxes, location. Pure Lua; calls Java only to turn a dimension name into its id (`J.dimension_id`). |
| [tasks.lua](src/main/resources/lullaby/lib/tasks.lua) | `run`, `wait`, `stop` and the scheduler that resumes waiting tasks (coroutines) each tick. |
| [events.lua](src/main/resources/lullaby/lib/events.lua) | `on`, `after`, event objects. |
| [entities.lua](src/main/resources/lullaby/lib/entities.lua) | Player, entity, client_entity, damage, profile, effect, blueprint; `modify_entity{}`, `teleport{}`, `remove_entity{}`, `blueprint{}`, `summon{}`, `clientside_summon{}`. Also what every object kind shares (`I.object_meta`, `I.object_kind`) and `I.from_java` (with its "tree": a table of values from Java, e.g. `display_data`). |
| [blocks.lua](src/main/resources/lullaby/lib/blocks.lua) | World objects (`world()`), block, block state, block snapshot, chunk. |
| [effects.lua](src/main/resources/lullaby/lib/effects.lua) | `message{}`, `particle{}`, `sound{}`, `precise_sound{}`, `stop_sound{}`, `sound_seed()`, the played sound value. |
| [lazy.lua](src/main/resources/lullaby/lib/lazy.lua) | The wire, item stacks, components, `from_json`, Lullaby's own `pairs`/`next` (LuaJIT ignores `__pairs`). |
| [commands.lua](src/main/resources/lullaby/lib/commands.lua) | `command{}`, `refresh_commands{}`, `online_players()`. |
| [data.lua](src/main/resources/lullaby/lib/data.lua) | `server.data`, `w.data`, `c.data` and `entity.data` (`player.data` is the same field): plain Lua tables while running, turned into save trees for SavedData.java, ChunkData.java and EntityData.java and back; the kept tables of unloaded chunks; locked tables. |
| [worlds.lua](src/main/resources/lullaby/lib/worlds.lua) | World folders: list, create, load, unload, copy (waits for the worker), delete. Turns world and location objects into names and numbers; Worlds.java checks everything else. |
| [loader.lua](src/main/resources/lullaby/lib/loader.lua) | Loading script files, `import`, and the code of `/lullaby run` (its own globals plus `me`, run as a task). |
| [java.lua](src/main/resources/lullaby/lib/java.lua) | The raw Java door's Lua side (table `java`, only when `unlock.java` is on) and its docs page. |

Other files:

- [defaults/](src/main/resources/defaults/config.lua): `config.lua` and `lullaby.lua`, copied to `plugins/lullaby` on
  the first start.
- [paper-plugin.yml](src/main/resources/paper-plugin.yml): the plugin descriptor (Paper's newer kind; its bootstrap
  step before worlds load is kept free for registry content later).
- [events.txt](src/main/resources/lullaby/events.txt): every Paper event scripts can listen to, with its fields,
  their kinds and setters. GENERATED at build by [EventList.java](tools/events/EventList.java) from Paper's API jar
  (Gradle task `events`), which owns the rules (what an event and a field is, the naming rule, the kinds); the format
  is in the file's header. It is in the repo because the docs site reads it too. Never edited by hand.
- [tools/docs/](tools/docs/Docs.java): the docs site builder (`Docs.java`, `site.html`, the guide, Lua included, in
  [guide.txt](tools/docs/guide.txt)). Not part of the plugin. It makes one page per event from `events.txt` and the hand
  marks in [event-marks.txt](tools/docs/event-marks.txt) (tested, busy, thread), which give the status dot.
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
   call Lua or touch live world objects. (The block worker builds block edits from plain data; see below.)
4. Every class that runs work off the main thread says so in its Javadoc.

Today eight places meet other threads:

- [Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java) runs one thread that only compares
  two numbers and raises a flag; the main thread checks that flag at every library call into Java.
- Paper builds each player's command tree on its own threads, so in
  [ScriptCommands.java](src/main/java/io/github/kupfermeister/lullaby/nms/ScriptCommands.java) Brigadier's `requires`
  checks and suggestions never call Lua: they read a per-player snapshot that Lua computes on the main thread (on
  join, on reload, on `refresh_commands{}`).
- [Input.java](src/main/java/io/github/kupfermeister/lullaby/nms/Input.java): while a script listens to `input`,
  a hook on the network threads wraps the input packets (it reads one field and touches nothing else). Vanilla queues
  them for the main thread as it queues every packet, in their order; the handlers run there.
- [SavedData.java](src/main/java/io/github/kupfermeister/lullaby/nms/SavedData.java): after an autosave each file is
  written on the one data writer thread from bytes made on the main thread; every other step on a file waits for its
  write first. [ChunkData.java](src/main/java/io/github/kupfermeister/lullaby/nms/ChunkData.java) loads a chunk in the
  background (Paper) to write a kept table's change; the answer comes back on the main thread and is dropped after a
  reload. Tab completion of `/lullaby data` paths may be asked for off the main thread: the keys are then worked out on the
  main thread and handed back later ([LullabyCommand.java](src/main/java/io/github/kupfermeister/lullaby/LullabyCommand.java)).
- [Worlds.java](src/main/java/io/github/kupfermeister/lullaby/nms/Worlds.java): world folders are copied, and temp is
  emptied at start, on its one worker thread, which touches only folders no world uses (a temp folder, an unloaded source, a target that
  doesn't exist yet). While a job runs, its world names are refused by every other world function; the finished job
  comes back through a queue that the main thread reads each tick, and a waiting `copy_world{}` asks for it once per
  tick.

- [BlockEngine.java](src/main/java/io/github/kupfermeister/lullaby/nms/BlockEngine.java): the one block worker thread
  runs build jobs that only read plain data and vanilla's unchangeable registries and block states (state text to
  state number, turning states); the result comes back through a queue the main thread reads each tick. The light
  engine's callback after a write hands its chunk to that queue too. A viewer's pipeline hook (Netty's threads) reads
  only that viewer's current fake blocks, an unchangeable snapshot the main thread swaps in whole.
- [mosaic/](src/main/java/io/github/kupfermeister/lullaby/mosaic/package-info.java): one job thread (with a download pool and a
  work pool) reads files and works on plain arrays only; its chat lines go through Bukkit's scheduler; the packets are
  built on the main thread ([MosaicSender.java](src/main/java/io/github/kupfermeister/lullaby/nms/MosaicSender.java)) and a
  playback thread only hands them to `connection.send` at their time.
- [LinkServer.java](src/main/java/io/github/kupfermeister/lullaby/lullablend/LinkServer.java): one thread accepts
  Blender's connections, each has a reader and a writer thread. They turn bytes into plain data and hand it to the
  main thread ([LullaBlend.java](src/main/java/io/github/kupfermeister/lullaby/lullablend/LullaBlend.java) `post`);
  they never touch the game, sessions or Lua.

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
- Game objects (player, entity, world, block, chunk, ...) exist once each, so `==` works. Reading functions turn
  text into objects (`player("Notch")`, `world("overworld")`) and give nil if there is none. A player is an entity
  too: every entity field and entity action work on players.
- Reading a name a value, object or event doesn't have is an error that lists what it has (`I.no_field` in
  core.lua), never nil, so a typo can't hide. A field that exists but has nothing reads nil (an item's health).
  Objects change only through actions: `modify_entity{ entity = e, velocity = v }` uses the same names as the fields read.
- Event fields come from Paper's getters and setters by one rule (`getJoinMessage()` → `e.join_message`,
  `isCancelled()` → `e.cancelled`), writable in `on`, read only in `after`.
- A change goes where the value lives. A list field (`e.drops`) reads as a plain Lua list, a block snapshot
  (`e.new_state`) as a `block_snapshot`; the same one for every read in the handler. When the handler ends or first
  waits, a changed one goes back into the event (events.lua `flush`, EventBridge `setList`/`setSnapshot`); where it
  can't (in `after`, or a field Paper doesn't let change) that is a red error at the line that read it. Which lists
  without a setter may change is EventList.java's `CHANGEABLE_LISTS` (the lists Paper's docs call mutable).

**Speed.** A library function with named arguments is a thin Lua wrapper that reads the `{ }` fields and calls Java
once with plain values; Java never reads a Lua table field by field (each read is a slow Lua-Java trip). Several
fields go as one wire table. An event no script uses has no listener, so it costs nothing. Every speed claim names
its measurement; the one so far: an event field read went from 2.9 to 1.3 microseconds (200000 reads, `os.clock`,
test server) when fields moved from names to numbers.

**Checks.** Every value from a script is checked exactly once, where it becomes a game thing. Lua checks only what
Java can't see (is it a function, a player object, was `{ }` given); Java checks everything that arrives over the
wire, through [Fields.java](src/main/java/io/github/kupfermeister/lullaby/nms/Fields.java). Errors name the field,
what it needs and what it got: `count must be a whole number, got 2.5`. A field a doing function doesn't take is an
error too (`particle{}: unknown field cuont (it takes ...)`), never ignored: Java's `Fields.done()` refuses every key
the action's code didn't read; functions checked in Lua use `I.only` (core.lua) with Java's wording. Errors of a
doing function start with its name and `{}` (`sound{}: ...`). One exception (jft 2026-11-23): an entity field that
this entity doesn't have (`food` on a zombie, `display_data` on a pig) is checked and then ignored, so one call can go
to entities of every sort (Entities.java's `SKIP`); a name no entity has is still an error.

**One owner per list.** Java owns the game's facts (wire kinds, events and their fields, event stages, sound
sources); the library reads them once at startup. The events come from Paper itself: EventList.java writes them into
`events.txt` at build, and EventBridge.java and the docs read that file. Doc blocks own what a script function takes. When Java's list and
Lua's disagree, loading stops with a message starting `Lullaby bug:`. Nothing looks things up by a position number.

**The engine.**
- Version lock: it runs only on the exact Minecraft version it was built for. No compatibility layers: what is
  replaced is removed.
- Dangerous Lua parts (`io`, `os`, `ffi`, `debug`, C modules) and the raw Java door are locked by default and unlocked in the config
  (README lists what each allows). Deliberate power needs an unlock; one script must not break another by accident.
- Error format: `⚠ Error in <path> on line N` in red (warnings yellow, `Warning`), then gray lines: `via <event> →
  <calls>`, the Lua message without its `file:line:` prefix, and `N times in 10s` for collapsed repeats. The console
  gets the same lines without symbols (`->` instead of `→`, no `⚠`), because Windows consoles show them as garbage,
  all red (warnings all yellow); console colors go through Paper's component logger, log files stay plain. Details:
  [ErrorReporter.java](src/main/java/io/github/kupfermeister/lullaby/error/ErrorReporter.java).
- Every text players or the console see has a `// FORMAT:` comment (in Lua `-- FORMAT:`) right above it, so all of
  them can be found and polished: search for `FORMAT:`.
- Endless loops never kill the server and never stop a script silently: see
  [Watchdog.java](src/main/java/io/github/kupfermeister/lullaby/lua/Watchdog.java), including what it can't stop.

**Docs.** Everything scripts can use has a `--[[doc ... ]]` block right above its `function lib.NAME(`, in the same
change that adds or changes it. The format is at the top of [Docs.java](tools/docs/Docs.java). The block feeds the
docs site, VS Code autocomplete (a class per page with `field:` lines, `is:` for its parent, `@return` from a plain
`returns:` type list) and the Markdown copy; uses and used-by are found from the code, not written.
Every object (a type page, or a reading page with fields such as `world( )`) gets a page in the group Objects with
all its fields, those of its `is:` parent too, and how each changes: `set: field | how` lines for a plain assignment,
`sets: <object>` on a doing page whose arguments change the fields of the same name (`modify_entity{ }`). The build
stops if one of those names no field (`objects` in Docs.java).
Pages changed in the last three commits that changed any page are marked new or changed, brightest for the newest
(`fresh` in Docs.java, from git blame over each page's lines; uncommitted changes count as the newest), so nothing
has to be marked by hand. The GitHub job checks out the whole history for this.

## Build and test

- `gradlew build` builds `build/libs/lullaby-<version>.jar` and copies it to `run/plugins` (older Lullaby jars there
  are removed). The first build takes a few minutes: paperweight sets up the Minecraft sources once. The build also
  downloads vanilla's `sounds.json` (about 600 KB, SHA-1 checked) from Mojang once per Minecraft version and packs
  it into the jar (task `downloadSounds`): the server jar has none, and `sound_seed()` needs it.
- `gradlew runServer` builds, downloads the Paper jar named in `gradle.properties` once (SHA-256 checked) and starts a
  test server in `run/` (not in the repo): port 25566, 4 GB. Minecraft's EULA must be accepted in `run/eula.txt`
  first. Type `stop` to end it.
- `gradlew events` writes `src/main/resources/lullaby/events.txt` (also part of every build; it only runs again
  when the Paper version or EventList.java changes).
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
   `J.name(plain values)`, or `J.name(I.wire({ ... }))` for several fields. A doing function refuses fields it doesn't
   take: through the wire, send all of the `{ }` and call `a.done()` in Java (step 5); otherwise call
   `I.only(a, "name{}", NAME_TAKES, 2)` in Lua, with `local NAME_TAKES = I.takes(...)` above the doc block.
4. In the J functions file of that library file (the JavaFunctions row in Parts names them all: entities.lua →
   `EntityFunctions.java`, ...), inside `fill`, add `add(lua, "name", L -> { ... })`. Arguments are stack positions
   1, 2, ...; push the results and return how many. Text goes through `LuaText`. Use `add`, not `addFree` (`add`
   checks the script time limit; `addFree` is only for what the library needs while loading or reporting). A new
   library file gets its own `<Topic>Functions.java` in `lua/` (copy the shape of a small one, `EffectFunctions`),
   one `<Topic>Functions.fill(...)` line at the end of `JavaFunctions.fill`, its name in `LIBRARY` in
   [LuaRuntime.java](src/main/java/io/github/kupfermeister/lullaby/lua/LuaRuntime.java) and rows in both tables of
   Parts.
5. Anything that touches Minecraft's code goes into a static method in `nms` ([Nms.java](src/main/java/io/github/kupfermeister/lullaby/nms/Nms.java)
   or the class of its topic). Wire fields are read through `Fields`; call `a.done()` after the last read, before
   anything changes. Every new message text gets a `FORMAT:` comment.

Follows by itself: scripts see `lib.NAME` as a global; the docs site page, autocomplete and the Markdown copy come
from the doc block; "uses" and the grayed page for `J.name` are found from the code. A `function lib.NAME(` without a
doc block stops the docs build with its file and line.

### Events

Every Paper event is there already: a new one in Paper appears by itself with the next build (commit the changed
`events.txt` so the docs site shows it too). Lists of a known kind work by themselves (`list:<kind>`); a list Paper
means to be changed but gives no setter for goes into `CHANGEABLE_LISTS` in EventList.java, and a block snapshot
field that Paper places after the event into its `CHANGEABLE_SNAPSHOTS`. Fields whose Java type Lullaby can't handle yet give a clear red error
when read (`e.inventory can't be read yet: Lullaby has no Inventory for scripts yet`).

To let fields of a new Java type through (for example inventories):

1. In [EventList.java](tools/events/EventList.java), give the type a kind word in `readKind` (and `writeKind` if
   scripts may set it), and name the word in the header line `KINDS:`.
2. In [EventBridge.java](src/main/java/io/github/kupfermeister/lullaby/event/EventBridge.java), add the word to
   `Kind`. Reading needs nothing more there: `get` hands every value to
   [ToLua.java](src/main/java/io/github/kupfermeister/lullaby/lua/ToLua.java), which gets a `case` for the Java type,
   and `I.from_java` in [entities.lua](src/main/resources/lullaby/lib/entities.lua) gets the kind word.
3. Setting: plain values go through `set`; anything else gets a `set...` method in EventBridge, its J function in
   [EventFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/EventFunctions.java) and a branch in the
   event object's `__newindex` in [events.lua](src/main/resources/lullaby/lib/events.lua) (Lua sends the value's
   parts: a slot, numbers).
4. In [Docs.java](tools/docs/Docs.java), `fieldRow` shows the kind on the event pages; a kind word that is the id of
   a docs page (entity, vec3, ...) links there by itself.
5. `gradlew build`: the list is generated again with the new kind.

Lullaby's own `input` event is not a Paper event: to add an input packet, add its line to `Kind` in
[Input.java](src/main/java/io/github/kupfermeister/lullaby/nms/Input.java) (vanilla's packet type and its fields;
a new field also goes into `Field` and `read`), say in `refusal` and `refuse` whether and how vanilla refuses it,
and add its `tree:` lines to the input doc block in [events.lua](src/main/resources/lullaby/lib/events.lua). The docs
build stops if the tree and `Kind` don't name the same packets, fields and action words.

To mark an event (tested and measured: the dot gets its color; busy; may come off the main thread), add a line to
[event-marks.txt](tools/docs/event-marks.txt).

Follows by itself: the library asks for an event's fields the first time a script uses it; the "unknown event" error
names close events; events Paper always fires off the main thread (`Async...`) are refused with a clear error, and any
other event that comes off the main thread is skipped (said once in the console); a word in `events.txt` that
EventBridge.java doesn't know, or a method this Paper doesn't have, stops with `Lullaby bug: ...`; until a script uses
an event, no listener exists and its class isn't even loaded.

### Add a packet action

1. In [Packets.java](src/main/java/io/github/kupfermeister/lullaby/nms/Packets.java), add
   `public static void name(Object wire, IntFunction<Object> slots)`: `Fields a = new Fields("name{}", wire)`, read
   each field with vanilla's own default, then `var to = receivers(a, slots); a.done();` (refuses fields nobody
   read), build the vanilla packet(s), then `send(to, packets)`. Field names are Mojang's, snake_case.
2. In [EffectFunctions.java](src/main/java/io/github/kupfermeister/lullaby/lua/EffectFunctions.java), add
   `add(lua, "name", L -> { Packets.name(Wire.read(L.toBuffer(1)), slotLookup); return 0; });`.
3. In [effects.lua](src/main/resources/lullaby/lib/effects.lua), add the doc block (with
   `expert: Packet | <the packet's protocol wiki name> (play, clientbound)`) and
   `function lib.name(a) J.name(I.wire(action_table(a, "name"))) end` (`action_table` copies every field and puts
   the receivers' slots in; replace object fields by their slot there, as `sound{}` does for `entity`).

Follows by itself: the player/players check and its error, every field error's wording (from `Fields`), the error
for a field the action doesn't take, bundling
when there is more than one packet, building each packet once for all receivers, the docs page.

### Update to a new Minecraft version

1. In [gradle.properties](gradle.properties) set `minecraftVersion`, `paperBuild` and `paperJarSha256` (the Paper
   build and its SHA-256 are on Paper's downloads page).
2. `gradlew build`. The first build sets up the new Minecraft sources (a few minutes). It also writes `events.txt`
   again from the new Paper: commit it, so the docs site lists the new version's events.
3. Fix what no longer compiles. It should all be in `nms`; anything outside is a change in Paper's own API.
4. `gradlew runServer`. The startup checks stop loading with `Lullaby bug: ...` if vanilla renamed something Lullaby
   reads (event getters and setters, wire kinds, input packets and their action words). Fix those in `nms` or
   `EventBridge` (new input actions also need their `tree:` lines in the input doc block).
5. Sounds: `sound_seed()` follows by itself (the build downloads the new `sounds.json`). A seed a script wrote as a
   plain number now may give another file if Mojang changed that sound's list. If the game's file pick itself
   changed (the classes named in [SoundSeeds.java](src/main/java/io/github/kupfermeister/lullaby/nms/SoundSeeds.java)),
   change it there too.
6. `gradlew docs`: the site shows the new version (read from `gradle.properties`).

Follows by itself: the plugin's `api-version` and the version lock use the new version; old jars refuse the new
server and say which build is needed.

## Plans (not built)

Marked so nobody mistakes them for features: event fields Lullaby can't read yet (inventories, menus, lists of
those, merchant offers, ...; `?` kinds in events.txt), new block snapshots in a changed list (only existing ones can be
kept, removed or given another state), and events Paper fires off the main thread (chat, pre-login); saved data on
items (`server.data`, `w.data`, `c.data` and `entity.data` exist so far); a generated packet system (`on_packet`, `send_packet`) on the same send core;
inventories; more entity fields and actions (equipment, effects, full NBT in blueprints); changing a client_entity's viewers; a separate particle engine on clientside entities (batching, short moves);
script actions on the block engine (`fill{}`, pasting schematics); mixins as an optional add-on.
