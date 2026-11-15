-- Lullaby config. Changes apply on /lullaby reload.
return {
  -- Lua libraries scripts may use. All locked (false) by default, because each one can harm the server.
  unlock = {
    io = false,      -- reading and writing any file (also dofile and loadfile)
    os = false,      -- the operating system: run programs, delete files, the clock
    ffi = false,     -- call any native code directly
    debug = false,   -- look into and change other functions, including Lullaby's own
    modules = false, -- require, module and native (C) modules; scripts use import instead
  },

  -- Warn when a script waits longer than a day (tasks end on reload and restart; store a time in script data instead).
  long_wait_warning = true,

  -- Lullaby's file functions may only touch the folders listed here.
  file_safety = true,
  file_whitelist = {
    "plugins/lullaby",
    -- "world", -- whitelist world folders here, for world managers for example
  },

  -- The same error repeating within this many seconds is counted instead of shown again.
  error_repeat_seconds = 10,

  -- Most particles one particle{} call may send (positions x count; count 0 counts as 1). false: no limit.
  particle_limit = 100000,
}
