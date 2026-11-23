-- Lullaby library: shared setup, error reports, config and the script sandbox.
--
-- Every library file is run once per Lua state (each start and each reload) by LuaRuntime.java and gets three tables:
--   J   raw Java functions (JavaFunctions.java). Private: scripts never see J.
--   lib the public library: everything scripts can use as globals (each script file has its own globals in front).
--   I   internal functions shared by the library files and called from Java (I.tick, I.dispatch, ...).
-- Everything here runs on the server main thread only.
--
-- Library functions take named arguments in { } and check them in Lua, then call Java once with plain values
-- (Kupfermeister's choice "B": one Lua-Java trip per call instead of one per field).
-- FORMAT: the texts in error(...) and I.warn(...) calls of all library files are shown to players and the console.
local J, lib, I = ...

-- Private copies: scripts can replace their own globals, and the sandbox below removes some of these from _G.
local debug_getinfo = debug.getinfo
local xpcall, tostring, select, unpack, type = xpcall, tostring, select, unpack, type
local concat = table.concat

I.MAIN = {} -- stands for "not inside any coroutine" where a coroutine is used as a table key

-- ---------------------------------------------------------------------------------------------------------------
-- Error reports
-- ---------------------------------------------------------------------------------------------------------------

local SCRIPT_PREFIX = "@scripts/" -- chunk name prefix of script files (see loader.lua), so frames can be told apart

local function is_script(info)
	return info.source:sub(1, #SCRIPT_PREFIX) == SCRIPT_PREFIX
end

-- Script frames of a stack, innermost first. co = a coroutine, or nil for the current stack (from `level` on).
-- Lua names a function by how its caller called it, so a name is kept only when a script called it: a function
-- started by run{} or an event is called by the library (as `f`), and that name would mean nothing to the scripter.
local function script_frames(co, level)
	local all = {}
	while true do
		local info
		if co then info = debug_getinfo(co, level, "Sln") else info = debug_getinfo(level, "Sln") end
		if not info then break end
		all[#all + 1] = info
		level = level + 1
	end
	local frames = {}
	for i, info in ipairs(all) do
		if is_script(info) then
			if not (all[i + 1] and is_script(all[i + 1])) then info.name = nil end
			frames[#frames + 1] = info
		end
	end
	return frames
end

-- How a frame's function is called in the via line: its name, "function at line N" for an unnamed function, nil for
-- the top of a file.
local function frame_name(info)
	if info.what == "main" then return nil end
	if info.name then return info.name end
	return "function at line " .. info.linedefined -- FORMAT
end

-- Sends an error or warning to Java (ErrorReporter.java formats, collapses repeats, shows console + ops).
-- kind: "error" or "warning"; co/level: where to look for the script line; origin: what started the code
-- (e.g. "PlayerJoinEvent (on)"), shown first in the "via" line.
function I.report(kind, co, level, message, origin)
	message = tostring(message)
	local frames = script_frames(co, level)
	local file, line = "", -1
	if frames[1] then
		file = frames[1].source:sub(2)
		line = frames[1].currentline
	end
	-- Lua's own "file:line: " prefix knows the line when the stack does not (e.g. errors from the Lua VM itself)
	local prefix_file, prefix_line = message:match("^([^\n]-):(%d+): ")
	if prefix_file and prefix_file:sub(1, 8) == "scripts/" and (file == "" or file == prefix_file) and line <= 0 then
		file, line = prefix_file, tonumber(prefix_line)
	end
	local via = {}
	if origin then via[1] = origin end
	for i = #frames, 1, -1 do
		local name = frame_name(frames[i])
		if name then via[#via + 1] = name end
	end
	message = message:gsub("^[^\n]-:%d+: ", "", 1) -- cut Lua's own "file:line: " prefix
	J.report(kind, file, line, concat(via, " → "), message)
end

-- A yellow warning about the script line `level` levels up (2 = the caller of the library function).
function I.warn(message, level)
	I.report("warning", nil, level + 1, message, I.current_origin())
end

-- Calls f(...) and reports an error instead of raising it. Returns ok, result.
function I.protected(origin, f, ...)
	local n, args = select("#", ...), { ... }
	return xpcall(function() return f(unpack(args, 1, n)) end, function(message)
		I.report("error", nil, 2, message, origin)
		return message
	end)
end

-- ---------------------------------------------------------------------------------------------------------------
-- Kinds, slots and type_of (design: tools/docs/design/types.lua)
-- ---------------------------------------------------------------------------------------------------------------

-- Lullaby's own things -> their type name ("player", "event", "task", "location", ...). Weak keys: an entry goes when
-- its thing is no longer used anywhere.
I.kind_of = setmetatable({}, { __mode = "k" })
local kind_of = I.kind_of

-- Slots (Slots.java): an object's number on the Java side. When Lua's garbage collector frees an object, a proxy
-- (LuaJIT 5.1 runs __gc only for userdata, so each object gets a tiny one) notes its number; I.flush_slots hands
-- all of them to Java in one call per tick. The object holds nothing of the proxy: the weak-key table does, so the
-- object can be collected, and the proxy goes with it.
I.slot_of = setmetatable({}, { __mode = "k" })
local slot_of = I.slot_of
local anchors = setmetatable({}, { __mode = "k" }) -- object -> its proxy
local freed, freed_n = {}, 0

function I.give_slot(object, slot)
	slot_of[object] = slot
	local proxy = newproxy(true)
	getmetatable(proxy).__gc = function()
		freed_n = freed_n + 1
		freed[freed_n] = slot
	end
	anchors[object] = proxy
end

-- The new list is made BEFORE the swap: making a table can run a garbage collection step, and a __gc that runs
-- then must add to the list that is still current, or its slot would be lost until reload.
function I.flush_slots()
	if freed_n == 0 then return end
	local fresh = {}
	local old, n = freed, freed_n
	freed, freed_n = fresh, 0
	J.free_slots(concat(old, "\n", 1, n))
end

-- Frees an object's slot NOW (for short-lived helper objects the library makes and drops at once), instead of
-- waiting for the garbage collector and the next tick.
function I.release(object)
	local proxy = anchors[object]
	if not proxy then return end
	getmetatable(proxy).__gc = nil
	anchors[object] = nil
	local slot = slot_of[object]
	slot_of[object] = nil
	J.free_slots(tostring(slot))
end

-- Frees a slot that no object holds any more (an event nobody waited on): handed to Java with the next tick's list,
-- so it costs no Java call and no garbage collection.
function I.free_slot(slot)
	freed_n = freed_n + 1
	freed[freed_n] = slot
end

-- LuaJIT's built-in 64-bit whole numbers (cdata, written 5LL) count as numbers. Their tostring is "5LL"/"5ULL".
local function is_int64(value)
	return type(value) == "cdata" and tostring(value):find("^%-?%d+U?LL$") ~= nil
end
I.is_int64 = is_int64

-- Values (values.lua) are known by their metatable, so making one needs no bookkeeping. The library reads hidden
-- metatables (__metatable = false) with its private copy of debug.getmetatable; scripts can't.
I.kind_by_meta = {}
local kind_by_meta = I.kind_by_meta
I.value_fields = {} -- value type name -> its field names in order (values.lua value_type; data.lua saves by them)
local debug_getmetatable = debug.getmetatable
I.debug_getmetatable = debug_getmetatable

-- type_of(x): the Lullaby type name of any value: "player", "vec3", "event", "number", "string", ...
--[[doc
name: type_of( )
kind: reading
group: Values
words: kind check
syntax: type_of( value ) → text
lead: The Lullaby type name of any value: "player", "vec3", "event", "number", ... Text gives "string", as Lua calls it: type_of("hi") == "string".
arg: value | any | Anything.
returns: text
expert: Thread | main thread, like every script
expert: Cost | 20–100 ns (guess, not measured)
expert: Under the hood | plain Lua: a weak table of Lullaby objects, then the metatable
expert: Can fail | never
expert: Reload | nothing to undo
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.type_of(value)
	local kind = kind_of[value]
	if kind then return kind end
	local t = type(value)
	if t == "table" then
		local meta = debug_getmetatable(value)
		return meta and kind_by_meta[meta] or "table"
	end
	if t == "cdata" and is_int64(value) then return "number" end
	return t
end
I.type_name = lib.type_of -- readable type for error messages

-- What a doing function's { } takes, for I.only: `positional` list entries (a[1], a[2], ...) and the named fields,
-- the same names the function's code reads. Made once per function, when its file loads.
function I.takes(positional, ...)
	local names = { ... }
	local set = {}
	for i = 1, #names do set[names[i]] = true end
	return { positional = positional, set = set, text = concat(names, "\n") }
end

-- Refuses every key of a doing function's { } that it doesn't take, so a typo (stage = "hihg" is checked, but
-- stgae = "high" would be ignored) is an error instead of doing nothing. The wording is Java's (Fields.unknownField),
-- so these errors read exactly like those of the actions Java checks (particle{}, modify_entity{}, ...). level: as error().
local raw_next = next
function I.only(a, what, takes, level)
	local set, positional = takes.set, takes.positional
	for key in raw_next, a do
		if not set[key] and not (type(key) == "number" and key >= 1 and key <= positional and key % 1 == 0) then
			error(J.unknown_field(what, type(key) == "string" and key or "[" .. tostring(key) .. "]", takes.text),
				level + 1)
		end
	end
end

-- Reading a name a value, object or event doesn't have is an error, not nil: a typo (e.helth) would otherwise read
-- nil and fail somewhere else. `has` lists what it has. level: 2 (objects, events: the script's line) or 0 (values:
-- the report finds the line).
function I.no_field(kind, key, has, level)
	-- FORMAT: a field a value or object doesn't have
	error(kind .. " has no field " .. (type(key) == "string" and key or "[" .. tostring(key) .. "]")
		.. " (it has " .. has .. ")", level == 0 and 0 or level + 1)
end

-- Number -> text: the SHORTEST text that reads back as exactly the same number (Lua's own tostring and `..` use 14
-- digits: 1297632424.123456 would become "1297632424.1235"). Whole numbers print without ".0" or exponent.
local format, floor, huge = string.format, math.floor, math.huge
local TWO53 = 2 ^ 53
function I.number_text(n)
	if n ~= n then return "nan" end
	if n == huge then return "inf" end
	if n == -huge then return "-inf" end
	if n == floor(n) and n > -TWO53 and n < TWO53 then return format("%d", n) end
	local text = format("%.15g", n)
	if tonumber(text) == n then return text end
	text = format("%.16g", n)
	if tonumber(text) == n then return text end
	return format("%.17g", n)
end

-- Lullaby's tostring (scripts see this one): exact numbers, 64-bit numbers without "LL"; everything else as Lua's.
local lua_tostring = tostring
--[[doc
name: tostring( )
kind: reading
group: Values
words: text number string
syntax: tostring( value ) → text
lead: Like Lua's tostring, but numbers are exact (the shortest text that reads back as the same number): 1297632424.123456 stays 1297632424.123456, where Lua would make 1297632424.1235.
arg: value | any | Anything.
returns: text
expert: Thread | main thread, like every script
expert: Cost | numbers 0.3–2 µs (tries 15, 16, then 17 digits), other values 50–300 ns (guess, not measured)
expert: Under the hood | plain Lua: string.format, then Lua's own tostring
expert: Can fail | never
expert: Reload | nothing to undo
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.tostring(value)
	local t = type(value)
	if t == "number" then return I.number_text(value) end
	if t == "cdata" and is_int64(value) then return (lua_tostring(value):gsub("U?LL$", "")) end
	return lua_tostring(value)
end

-- ---------------------------------------------------------------------------------------------------------------
-- Config (plugins/lullaby/config.lua) and the sandbox
-- ---------------------------------------------------------------------------------------------------------------

local DEFAULTS = {
	unlock = { io = false, os = false, ffi = false, debug = false, modules = false, java = false },
	long_wait_warning = true,
	error_repeat_seconds = 10,
	particle_limit = 100000,
	clientside_entity_limit = 1000000,
	script_time_limit = 5,
	data_max_size = 16,
}

-- Reads config.lua (a Lua file that returns a table) with nothing in reach, fills in defaults for missing keys.
local function read_config()
	local config = {}
	local source = J.read_config()
	if source then
		local chunk, err = loadstring(source, "@config.lua")
		local ok, result
		if chunk then
			setfenv(chunk, {})
			ok, result = pcall(chunk)
			if ok and type(result) ~= "table" then ok, result = false, "config.lua must return a table { ... }" end
		else
			result = err
		end
		if ok then
			config = result
		else
			local line = tonumber(tostring(result):match(":(%d+): ")) or -1
			J.report("error", "plugins/lullaby/config.lua", line, "loading the config",
				tostring(result):gsub("^[^\n]-:%d+: ", "", 1) .. " (using the defaults)")
		end
	end
	for key, value in pairs(DEFAULTS) do
		if config[key] == nil then config[key] = value end
	end
	-- particle_limit, clientside_entity_limit: false (off) or a whole number >= 1 (at most Java's int range); anything
	-- else warns and uses the default.
	for _, key in ipairs({ "particle_limit", "clientside_entity_limit" }) do
		local limit = config[key]
		if limit ~= false then
			limit = tonumber(limit)
			if not limit or limit < 1 or limit ~= math.floor(limit) or limit > 2147483647 then
				-- FORMAT: bad particle_limit or clientside_entity_limit in the config
				J.report("warning", "plugins/lullaby/config.lua", -1, "loading the config",
					key .. " must be false or a whole number of 1 or more (using " .. DEFAULTS[key] .. ")")
				limit = DEFAULTS[key]
			end
			config[key] = limit
		end
	end
	-- script_time_limit: false (off) or a number of seconds above 0; anything else warns and uses the default.
	local time = config.script_time_limit
	if time ~= false and (type(time) ~= "number" or not (time > 0) or time == math.huge) then
		-- FORMAT: bad script_time_limit in the config
		J.report("warning", "plugins/lullaby/config.lua", -1, "loading the config",
			"script_time_limit must be false or a number of seconds above 0 (using " .. DEFAULTS.script_time_limit .. ")")
		config.script_time_limit = DEFAULTS.script_time_limit
	end
	if type(config.unlock) ~= "table" then config.unlock = {} end
	for key, value in pairs(DEFAULTS.unlock) do
		if config.unlock[key] == nil then config.unlock[key] = value end
	end
	return config
end

-- load and loadstring for scripts (one function serves both: LuaJIT's load also takes text). Text only: LuaJIT
-- bytecode is not checked when it loads, so hand-made bytecode could break out of the sandbox. The loaded code runs
-- with the calling script's globals, not the library's _G.
local raw_load, setfenv_, getfenv_ = load, setfenv, getfenv
local function script_load(chunk, name)
	local f, err = raw_load(chunk, name, "t")
	if f then setfenv_(f, getfenv_(2)) end
	return f, err
end

-- Standard tables every script file gets as its OWN copy (loader.lua calls this per file): a script that adds
-- string.trim or replaces math.floor changes only its own copy, never other scripts or the library. Method calls on
-- strings (s:upper()) always use the original string functions.
local STD = { string = string, table = table, math = math, coroutine = coroutine, bit = bit }
function I.std_copies(env)
	for name, original in pairs(STD) do
		local copy = {}
		for key, value in pairs(original) do copy[key] = value end
		env[name] = copy
	end
	env.coroutine.resume = script_resume
end

-- Builds `lib` (what scripts see) from the safe standard functions plus unlocked libraries, then removes the
-- dangerous ones from the real _G too.
-- The `java` table LuaJava adds can reach any Java class: it is removed always. unlock.java gives scripts Lullaby's
-- own `java` table instead (java.lua): the same reach, but with Lullaby's errors and the script time limit.
-- unlock.debug also brings the functions that reach into the Lua state shared by every script: getfenv/setfenv
-- (other functions' globals, also the library's), collectgarbage/gcinfo (stopping the garbage collector), newproxy,
-- and jit (turning LuaJIT's compiler off for everyone).
-- pcall, xpcall and coroutine.resume for scripts: like Lua's, but a stop (stop{} of the running task, or the
-- script_time_limit) is passed on instead of caught, so a script can't swallow its own stop by accident.
local raw_pcall, raw_xpcall, raw_resume = pcall, xpcall, coroutine.resume
local function pass_stops(ok, ...)
	if not ok then
		local message = ...
		if message == I.TIME_UP or message == I.STOP then error(message, 0) end
	end
	return ok, ...
end
local function script_pcall(f, ...) return pass_stops(raw_pcall(f, ...)) end
local function script_xpcall(f, handler, ...) return pass_stops(raw_xpcall(f, handler, ...)) end
local function script_resume(co, ...) return pass_stops(raw_resume(co, ...)) end

local function build_sandbox(unlock)
	lib.pcall, lib.xpcall = script_pcall, script_xpcall
	for _, name in ipairs({ "assert", "error", "ipairs", "select", "tonumber",
		"type", "unpack", "rawequal", "rawget", "rawset", "getmetatable", "setmetatable", "_VERSION" }) do
		lib[name] = _G[name]
	end
	-- getmetatable("") would hand out the string table every string shares (and every script with it)
	debug_getmetatable("").__metatable = false
--[[doc
id: pairs
name: pairs( ) and next( )
kind: reading
group: Values
words: loop iterate table fields
syntax: for key, value in pairs(table) do ... end
lead: Lua's pairs and next, made to also work on item stacks: those load every part first. On other tables they work exactly like Lua's.
arg: table | table | The table.
expert: Thread | main thread, like every script
expert: Cost | plain tables: as Lua's own; an item stack: 5–50 µs once to load its parts (guess, not measured)
expert: Under the hood | Lua's next; an item stack's parts are loaded from Java first
expert: Can fail | only as Lua's pairs does (not a table)
expert: Reload | nothing to undo
expert: Time limit | not checked: plain Lua, no call into the game (an item stack's first load calls Java and is checked)
status: unconfirmed
]]
--[[doc
id: print
name: print( )
kind: reading
group: Values
words: log console debug output
syntax: print( ... )
lead: Writes the values to the server console, separated by tabs. Numbers are written exactly (see tostring( )).
arg: ... | any | Anything.
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | 5–50 µs, mostly the console writing (guess, not measured)
expert: Under the hood | one Java call to the server's logger
expert: Can fail | never
expert: Reload | lines stay in the console and log
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
--[[doc
id: pcall
name: pcall( ) and xpcall( )
kind: reading
group: Values
words: try catch error protected
syntax: local success, result = pcall( func, ... )
lead: Lua's pcall and xpcall: run func and catch its errors. A stop is never caught: stop{ } of the running task and the config's script_time_limit still end the task (coroutine.resume passes them on too).
arg: func | function | What to run.
arg: ... | any | Its arguments (xpcall: the error handler first).
returns: true and f's results · false and the error
expert: Thread | main thread, like every script
expert: Cost | 50–300 ns on top of the call (guess, not measured)
expert: Under the hood | Lua's pcall, plus a check that passes stops on
expert: Can fail | never itself; a stop (stop{ }, script_time_limit) is passed on, not caught
expert: Reload | nothing to undo
expert: Time limit | passed on: pcall can't catch it
status: unconfirmed
]]
	lib.pairs, lib.next = I.pairs, I.next -- Lullaby's own (lazy.lua): they load lazy values first
	lib.load, lib.loadstring = script_load, script_load
	lib.print = function(...)
		local parts = {}
		for i = 1, select("#", ...) do parts[i] = lib.tostring((select(i, ...))) end
		J.log(concat(parts, "\t"))
	end
	if unlock.io then lib.io, lib.dofile, lib.loadfile = io, dofile, loadfile end
	if unlock.os then lib.os = os end
	if unlock.ffi then lib.ffi = require("ffi") end
	if unlock.debug then
		lib.debug, lib.jit, lib.getfenv, lib.setfenv = debug, jit, getfenv, setfenv
		lib.collectgarbage, lib.gcinfo, lib.newproxy = collectgarbage, gcinfo, newproxy
	end
	if unlock.modules then lib.require, lib.module, lib.package = require, module, package end
	if unlock.java == true then lib.java = I.java_door() end
	for _, name in ipairs({ "io", "os", "debug", "require", "module", "package", "dofile", "loadfile", "java" }) do
		_G[name] = nil
	end
end

-- Called by Java once all library files have run, before the scripts load.
function I.setup()
	I.check_kinds()
	I.config = read_config()
	-- The exact error text of a script stopped by the time limit (Watchdog.java), so the pcall guards know it.
	I.TIME_UP = J.set_time_limit(I.config.script_time_limit or 0)
	J.set_error_window(tonumber(I.config.error_repeat_seconds) or 10)
	I.data_setup(I.config) -- data.lua
	build_sandbox(I.config.unlock)
end
