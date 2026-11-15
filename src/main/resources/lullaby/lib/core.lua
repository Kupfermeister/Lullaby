-- Lullaby library, file 1 of 8: shared setup, error reports, config and the script sandbox.
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

-- How a frame's function is called in reports: its name, "at line N" for an unnamed function, nil for the top of a
-- file. `head` is for the first line ("(function greet)" / "(function at line 12)"), otherwise for the via line.
local function frame_name(info, head)
	if info.what == "main" then return nil end
	if info.name then return info.name end
	return (head and "" or "function ") .. "at line " .. info.linedefined -- FORMAT
end

-- Sends an error or warning to Java (ErrorReporter.java formats, collapses repeats, shows console + ops).
-- kind: "error" or "warning"; co/level: where to look for the script line; origin: what started the code
-- (e.g. "PlayerJoinEvent (on)"), shown first in the "via" line.
function I.report(kind, co, level, message, origin)
	message = tostring(message)
	local frames = script_frames(co, level)
	local file, line, fn = "", -1, ""
	if frames[1] then
		file = frames[1].source:sub(2)
		line = frames[1].currentline
		fn = frame_name(frames[1], true) or ""
	end
	-- Lua's own "file:line: " prefix knows the line when the stack does not (e.g. errors from the Lua VM itself)
	local prefix_file, prefix_line = message:match("^([^\n]-):(%d+): ")
	if prefix_file and prefix_file:sub(1, 8) == "scripts/" and (file == "" or file == prefix_file) and line <= 0 then
		file, line = prefix_file, tonumber(prefix_line)
	end
	local via = {}
	if origin then via[1] = origin end
	for i = #frames, 1, -1 do
		local name = frame_name(frames[i], false)
		if name then via[#via + 1] = name end
	end
	message = message:gsub("^[^\n]-:%d+: ", "", 1) -- cut Lua's own "file:line: " prefix
	J.report(kind, file, line, fn, concat(via, " → "), message)
end

-- A gold warning about the script line `level` levels up (2 = the caller of the library function).
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
-- Kinds, slots and type_of (Kupfermeister 2026-11-15, see types.lua)
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

function I.flush_slots()
	if freed_n == 0 then return end
	local list = concat(freed, "\n", 1, freed_n)
	freed, freed_n = {}, 0
	J.free_slots(list)
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
local debug_getmetatable = debug.getmetatable
I.debug_getmetatable = debug_getmetatable

-- type_of(x): the Lullaby type name of any value: "player", "vec3", "event", "number", "string", ...
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
	unlock = { io = false, os = false, ffi = false, debug = false, modules = false },
	long_wait_warning = true,
	file_safety = true,
	file_whitelist = { "plugins/lullaby" },
	error_repeat_seconds = 10,
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
			J.report("error", "plugins/lullaby/config.lua", line, "", "loading the config",
				tostring(result):gsub("^[^\n]-:%d+: ", "", 1) .. " (using the defaults)")
		end
	end
	for key, value in pairs(DEFAULTS) do
		if config[key] == nil then config[key] = value end
	end
	if type(config.unlock) ~= "table" then config.unlock = {} end
	for key, value in pairs(DEFAULTS.unlock) do
		if config.unlock[key] == nil then config.unlock[key] = value end
	end
	return config
end

-- Wraps load/loadstring so code loaded by a script runs with that script's globals, not the library's _G.
local function loader_for(original)
	return function(chunk, name)
		local f, err = original(chunk, name)
		if f then setfenv(f, getfenv(2)) end
		return f, err
	end
end

-- Builds `lib` (what scripts see) from the safe standard functions plus unlocked libraries, then removes the
-- dangerous ones from the real _G too, so getfenv(0) and similar tricks reach nothing locked.
-- The `java` table LuaJava adds can reach any Java class: it is removed always (raw doors come later as x.raw).
local function build_sandbox(unlock)
	for _, name in ipairs({ "assert", "error", "ipairs", "next", "pairs", "pcall", "xpcall", "select", "tonumber",
		"type", "unpack", "rawequal", "rawget", "rawset", "getmetatable", "setmetatable", "getfenv",
		"setfenv", "collectgarbage", "gcinfo", "newproxy", "_VERSION" }) do
		lib[name] = _G[name]
	end
	lib.string, lib.table, lib.math, lib.coroutine, lib.bit, lib.jit = string, table, math, coroutine, bit, jit
	lib.load, lib.loadstring = loader_for(load), loader_for(loadstring)
	lib.print = function(...)
		local parts = {}
		for i = 1, select("#", ...) do parts[i] = lib.tostring((select(i, ...))) end
		J.log(concat(parts, "\t"))
	end
	if unlock.io then lib.io, lib.dofile, lib.loadfile = io, dofile, loadfile end
	if unlock.os then lib.os = os end
	if unlock.ffi then lib.ffi = require("ffi") end
	if unlock.debug then lib.debug = debug end
	if unlock.modules then lib.require, lib.module, lib.package = require, module, package end
	for _, name in ipairs({ "io", "os", "debug", "require", "module", "package", "dofile", "loadfile", "java" }) do
		_G[name] = nil
	end
end

-- Called by Java once all library files have run, before the scripts load.
function I.setup()
	I.config = read_config()
	J.set_error_window(tonumber(I.config.error_repeat_seconds) or 10)
	build_sandbox(I.config.unlock)
end
