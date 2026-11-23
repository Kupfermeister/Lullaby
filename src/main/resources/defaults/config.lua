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
}
