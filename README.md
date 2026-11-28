# Lullaby

Lua scripting for Paper servers. You write small `.lua` files, Lullaby runs them inside the server: react to
events, add commands, send messages, particles and sounds. It is for server owners who want their own game rules
without writing a Java plugin, and for experts who want close-to-vanilla control without the slowness that usually
comes with scripting.

```lua
-- plugins/lullaby/scripts/hello.lua
register_command{ "hello", function(context)
  message{ player = context.player, text = "Hello, " .. context.player.name .. "!" }
end }
```

**Does it fit your server?**

I focus on adding at minimum all functionality Skript or Denizen has and making it very fast.

- **Not released yet.** There are no downloads; you build the jar yourself (below). Things still change.
- **Paper 26.3 only.** Lullaby runs only on the exact Minecraft version it was built for, and stays off on any other
  (it says so in the console). The version is `minecraftVersion` in [gradle.properties](gradle.properties).
- **Java 25.**

**Unique features?**
- precise sounds, aka you can play only music/game/creative/aria_math
- blenderbridge, an additional blender plugin that can hook into your server with lullaby and display meshes in real time
  (good building tool)
- sound mosaicing algorithm, put an mp3 into the according folder and play that mp3 using a mosaic with minecraft sound
- register bregardier commands (the ones where the args have different colors like in vanilla)
- (wip: register custom blocks / overwrite existing with clientside appearances)
- (wip: register custom items / overwrite existing with clientside appearances)
- (wip: custom particle engine suing text displays and atlas sprites)
- (wip: mannequin npcs with human movement)
- (wip: absolute light control, change block light and sky light level per block)

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

## Commands
/lullaby info|reload|run|data|blenderbridge
/lul (short version for /lullaby reload)

## License

MIT, see [LICENSE](LICENSE).
