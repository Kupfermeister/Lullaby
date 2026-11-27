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
  -- /lullaby mosaic test subtick plays note block hats ever closer together, to hear whether subtick works.
  sound_mosaicing = {
    -- "slices" (slice by slice: melody, bass, noise and detail layers) or "layers" (the strict long-sound solver)
    method = "slices",
    -- true: sounds start and stop between ticks, on 10 ms steps. false: on whole ticks (50 ms)
    subtick = false,
    -- with subtick: how long one slice is, 10 to 50 ms (10 is the finest)
    slice_ms = 10,
    -- each layer's loudness, 0 turns it off. detail: what is still missing above 1 kHz (consonants), 0 to 1
    volume = { melody = 1, bass = 1, noise = 1, detail = 0.5 },
    -- the input's loud parts equal about this many sounds at full volume. try 2 to 5
    loudness = 3,
    -- which pitches count most: "speech", "music" or your own { [100] = 0.5, [300] = 2 } (Hz = weight)
    weighting = "speech",
    -- how much worse a wrong sound counts than a missing one. 1 to 5; higher: cleaner but fewer sounds
    strictness = 2,
    -- how well a sound must fit to be used. 0 to 1; lower: more sounds, rougher. try 0.2 to 0.5
    similarity = 0.3,
    -- above 1: more sounds, denser. below 1: fewer. try 0.5 to 3
    saturation = 1,
    -- 1 to 4: look harder for fitting sounds (that many times slower)
    precision = 1,
    -- semitones between the pitches tried: 0.5 (finer) or 1 (twice as fast)
    pitch_step = 0.5,
    -- most sounds started in one tick
    max_per_tick = 40,
    -- most sounds playing at once, up to 247 (the game's channels)
    max_voices = 230,
    -- most music or disc sounds playing at once, 0 to 8
    max_streams = 4,
    -- one sound file at most count times in any window this many ticks long
    rate_limit = { count = 8, ticks = 60 },
    -- no sound plays longer than this many seconds
    max_play_seconds = 2,
    -- only a sound that hits again or stays loud this long may be stopped early; single hits (a step) play whole
    hold_seconds = 0.3,
    -- a sound is only cut off while what is left of it is at least this loud (share of its loudest moment); a
    -- quieter rest (a hit's tail) plays out. 0 to 1 (0: cut wherever needed; 1: almost never cut)
    cut_threshold = 0.3,
    -- the melody only uses sounds at least this tonal. 0 to 1 (1: one clear held tone)
    melody_tonality = 0.6,
    -- slices: how much of the input the melody gets first. 0 to 0.95 (0: only the clearly tonal part; higher: the
    -- melody takes nearly everything, noise keeps only what is sharp and clearly noisy, plus what melody can't play)
    melody_priority = 0.75,
    -- the bass is everything below this many Hz
    bass_below_hz = 250,
    -- bass sounds need at least this share of their energy below bass_below_hz. 0 to 1
    bass_purity = 0.8,
    -- false: never use music and discs (they are then not downloaded either, about 290 MB)
    music = true,
    -- /lullaby mosaic play <file> <name> uses only that list. * is one part of a sound id, ** any text, ! takes out
    filters = {
      noteblox = { "block.note_block.*", "block.anvil.land", "entity.experience_orb.pickup" },
    },
    -- set a file's tonality yourself, 0 to 1: { ["mob/cow/say1"] = 0.9 }
    tonality = {},
  },
}
