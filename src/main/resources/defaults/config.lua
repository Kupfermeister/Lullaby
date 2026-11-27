-- Lullaby config. Changes apply on /lullaby reload.
return {
  -- Lua libraries scripts may use. All locked (false) by default, because each one can harm the server.
  unlock = {
    io = false,      -- reading and writing any file (also dofile and loadfile)
    os = false,      -- the operating system: run programs, delete files, the clock
    ffi = false,     -- call any native code directly
    debug = false,   -- look into and change other functions, including Lullaby's own (also jit, collectgarbage,
                     -- getfenv, setfenv, gcinfo, newproxy)
    modules = false, -- require, module and native (C) modules; scripts use import instead
    java = false,    -- any Java directly: Paper, Minecraft's own classes, other plugins (the table java; see the
                     -- docs page "the Java door"). Can do anything at all, and breaks on Minecraft updates
    command_blocks = false, -- /lullaby run from command blocks: anyone who can edit one could run any code
  },

  -- Warn when a script waits longer than a day (tasks end on reload and restart; store a time in script data instead).
  long_wait_warning = true,

  -- The same error repeating within this many seconds is counted instead of shown again.
  error_repeat_seconds = 10,

  -- Most particles one particle{} call may send (positions x count; count 0 counts as 1). false: no limit.
  particle_limit = 100000,

  -- Most clientside entities (clientside_summon{}) that may exist at once, all scripts together. Each keeps an entity
  -- in the server's memory until it is removed or its lifetime ends, so a script stuck making them can't fill the
  -- memory. false: no limit.
  clientside_entity_limit = 1000000,

  -- Seconds one script may run without a break (until its handler ends or it waits). A script past it gets an error
  -- at its next call into the game and is stopped (its final still runs). A loop that never calls into the game
  -- (pure math) can't be stopped. false: no limit.
  script_time_limit = 5,

  -- Most megabytes each saved data file may take: server.data (plugins/lullaby/data/server.snbt) and each world's
  -- w.data (data/lullaby.snbt in the world's folder). c.data lives inside the chunks and has no limit. Past it, the data is not saved
  -- (the last saved file stays) and a red error says so, so a script stuck adding keys can't fill the disk.
  -- false: no limit.
  data_max_size = 16,

  -- The block engine (big block jobs such as LullaBlend's place): most milliseconds of writing blocks per tick (a tick
  -- has 50; more is faster but lags), and most changed sections (16x16x16 blocks) of fake blocks sent to one player
  -- per tick (more shows big previews faster but can stutter that player's game).
  block_write_ms = 10,
  block_show_sections = 128,

  -- LullaBlend: the port the Blender add-on connects to. false: off (no port is opened). Each player makes their own
  -- password for Blender in the game with /lullaby blenderbridge token (needs the permissions lullaby.command and lullaby.blenderbridge, ops have them).
  -- lullablend_bind: "0.0.0.0" takes connections from anywhere (open the port on your host for other computers),
  -- "127.0.0.1" only from this computer.
  lullablend_port = false,
  lullablend_bind = "0.0.0.0",

  -- Sound mosaicing: /lullaby mosaic play <file> [filters] [-overwrite] rebuilds an audio file (.wav, .mp3, .ogg) in
  -- plugins/lullaby/mosaic out of Minecraft's own sounds and plays it to you. A file is worked out once and kept
  -- (in mosaic/made, with a preview .wav); -overwrite works it out again, for example after changing these.
  sound_mosaicing = {
    method = "slices",     -- "slices": slice by slice, in a melody, a bass and a noise layer; "layers": the strict
                           -- solver that places long sounds (melody, bass, main and detail layers)
    subtick = false,       -- true: sounds start and stop between ticks (sent by a timing thread), on 10 ms steps
    slice_ms = 10,         -- slices with subtick: how long one slice is (10 to 50 ms); without subtick a slice is one tick
    volume = { melody = 1, bass = 1, noise = 1, detail = 0.5 }, -- each layer's loudness; 0 turns it off
                           -- (noise is the main layer of "layers"; detail is only used by "layers")
    loudness = 3,          -- the input's loud parts equal about this many sounds at full volume
    weighting = "speech",  -- which pitches count most: "speech", "music", or { [100] = 0.5, [300] = 2, ... } (Hz = weight)
    strictness = 2,        -- how much worse sound that isn't in the input counts than sound that is missing
    similarity = 0.3,      -- how well a sound must fit to be used (0 to 1)
    saturation = 1,        -- above 1: more sounds, denser; below 1: fewer
    precision = 1,         -- 1 to 4: look harder (that many times slower)
    pitch_step = 0.5,      -- semitones between the pitches tried: 0.5 or 1 (twice as fast)
    max_per_tick = 40,     -- most sounds started in one tick
    max_voices = 230,      -- most sounds playing at once (the game has about 247 channels)
    max_streams = 4,       -- most music or disc sounds playing at once
    rate_limit = { count = 8, ticks = 60 }, -- one sound file at most count times in any window this many ticks long
    max_play_seconds = 2,  -- no sound plays longer; longer ones are stopped by then
    hold_seconds = 0.3,    -- only a sound that hits again or stays loud this long may be stopped early; a single
                           -- hit (a step, the experience sound) always plays whole (each sound's verdict: made/sounds.txt)
    melody_tonality = 0.6, -- the melody only uses sounds at least this tonal (0 to 1: one clear held tone)
    bass_below_hz = 250,   -- the bass is everything below this, played with sounds that have at least
    bass_purity = 0.8,     -- this share of their energy below it
    music = true,          -- false: never use music and music discs (they are then not downloaded either, about 290 MB)
    filters = {            -- /lullaby mosaic play <file> noteblox uses only these sounds. Sound ids; * is one part of
                           -- an id (no dots), ** any text, ! in front takes sounds out
      noteblox = { "block.note_block.*", "block.anvil.land", "entity.experience_orb.pickup" },
    },
    tonality = {},         -- set a file's tonality yourself: { ["mob/cow/say1"] = 0.9 }
  },
}
