-- Lullaby library: saved data (server.data), which outlives /lullaby reload and restarts.
--
-- While the server runs, server.data is a PLAIN Lua table: reading and writing it costs what any Lua table costs, no
-- Java, no file. Only saving and loading turn it into something else:
--   save (Java asks: autosave, /save-all, server stop, before /lullaby reload): I.data_save walks the table once and
--     builds a SAVE TREE (plain tables, text keys or lists only) that goes to Java over the wire; Java writes it as
--     plugins/lullaby/data/server.snbt (nms/SavedData.java). What can't be saved (functions, entities, tables with their
--     own metatable, ...) is a red error naming its place (server.data.shop.on_buy) and is left out; the rest is saved.
--   load (each start and reload, before the scripts): Java reads the file and hands the tree back; I.data_load turns it
--     into Lua values again (players, worlds, positions, item stacks, ...).
-- In the save tree a Lullaby value is a table with ONE key "lullaby:<type>" (in the file: {"lullaby:vec3": {x: 1, ...}}).
-- So scripts can't use keys starting with "lullaby:" in server.data: they are refused with a clear error.
-- Shared by every script file (it is one table), unlike a file's own globals.
local J, lib, I = ...

local type, error, tostring, tonumber, rawget, pcall, unpack, setmetatable =
	type, error, tostring, tonumber, rawget, pcall, unpack, setmetatable
local raw_next = next
local find, sub, format, concat, sort = string.find, string.sub, string.format, table.concat, table.sort
local kind_of, kind_by_meta, meta_of, value_fields = I.kind_of, I.kind_by_meta, I.debug_getmetatable, I.value_fields

local PREFIX = "lullaby:"

-- Small values saved by their fields and made again with their maker (lib.<type>(field1, field2, ...)), which checks
-- what comes back from the file. Item stacks, text components and block states go through vanilla's codecs (Java).
local BY_FIELDS = { block_pos = true, section_pos = true, chunk_pos = true, vec3 = true, yaw_pitch = true,
	quaternion = true, aabb = true, bounding_box = true, global_pos = true, location = true }
local BY_CODEC = { item_stack = true, component = true, block_state = true }

local data = {} -- server.data

-- ---------------------------------------------------------------------------------------------------------------
-- Where a problem is: the keys from server.data down, kept as a stack (no text is built unless there is a problem)
-- ---------------------------------------------------------------------------------------------------------------

local path, depth = {}, 0

local function key_text(key)
	if type(key) == "number" then return "[" .. I.number_text(key) .. "]" end
	if type(key) ~= "string" then return "[" .. tostring(key) .. "]" end -- true, a table, ... (not saved anyway)
	if find(key, "^[%a_][%w_]*$") then return "." .. key end
	return "[" .. (format("%q", key):gsub("\\\n", "\\n")) .. "]" -- %q keeps a line break as \ + break: one line
end

local function place()
	local parts = { "server.data" }
	for i = 1, depth do parts[i + 1] = key_text(path[i]) end
	return concat(parts)
end

-- A list keeps its order or goes whole: leaving one entry out would move every entry after it. Says so once at the
-- last problem (a list inside a list doesn't say it twice).
local WHOLE_LIST = " (so the whole list is left out)" -- FORMAT
local function whole_list(list)
	local last = list[#list]
	if last and sub(last, -#WHOLE_LIST) ~= WHOLE_LIST then list[#list] = last .. WHOLE_LIST end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Lua -> save tree
-- ---------------------------------------------------------------------------------------------------------------

local problems -- list of texts while saving; nil while viewing (then a problem becomes a note in the view)
local on_path = {} -- tables on the current path, to find a table that holds itself

-- FORMAT: what can't be saved, said after "server.data.x can't be saved: "
local function why_not(v)
	local t = type(v)
	if t ~= "table" then return "it is a " .. I.type_name(v) end
	local kind = kind_of[v]
	if kind then return "it is a " .. kind .. " (of the game objects, only players and worlds are saved)" end
	local name = kind_by_meta[meta_of(v)]
	if name then return "it is a " .. name .. " (can't be saved yet)" end
	return "it is a table with its own metatable"
end

local copy

-- A problem at the current place: while saving it is collected (and the value left out), while viewing it becomes a
-- note text in the view, so /lullaby data shows what will be missing.
local function problem(why)
	-- FORMAT: one thing in server.data that can't be saved
	local text = place() .. " can't be saved: " .. why
	if problems then
		problems[#problems + 1] = text
		return nil
	end
	return "(not saved: " .. why .. ")" -- FORMAT: shown by /lullaby data in place of the value
end

local function copy_table(t)
	if on_path[t] then return problem("the table holds itself (a loop)") end
	-- Loading goes through the wire, which takes 100 levels counting server.data and the last values: so a table
	-- may be at most 98 levels below server.data.
	if depth > 98 then return problem("it is nested more than 98 tables deep inside server.data") end -- FORMAT
	-- A list (keys exactly 1..n) stays a list; anything else needs text keys.
	local n, list = 0, true
	for key in raw_next, t do
		n = n + 1
		if list and not (type(key) == "number" and key >= 1 and key % 1 == 0) then list = false end
	end
	if list and n > 0 then
		for i = 1, n do
			if rawget(t, i) == nil then list = false; break end
		end
	end
	on_path[t] = true
	local out = {}
	if list and n > 0 then
		for i = 1, n do
			depth = depth + 1; path[depth] = i
			local value = copy(rawget(t, i))
			if value == nil then
				depth = depth - 1
				on_path[t] = nil
				if problems then whole_list(problems) end
				return nil
			end
			out[i] = value
			depth = depth - 1
		end
	else
		for key, value in raw_next, t do
			depth = depth + 1; path[depth] = key
			if type(key) ~= "string" then
				-- FORMAT: a key that is not text in a table that is not a list
				local note = problem("its key is a " .. I.type_name(key)
					.. " (keys must be text, or the table a list 1, 2, 3, ...)")
				if note then out[tostring(key)] = note end
			elseif key == "" then
				-- FORMAT: the empty key (vanilla's SNBT reader can't read it back)
				local note = problem("its key is empty text \"\", which the file can't hold")
				if note then out["(empty key)"] = note end
			elseif sub(key, 1, #PREFIX) == PREFIX then
				-- FORMAT: a key Lullaby keeps for itself
				local note = problem("names starting with \"" .. PREFIX .. "\" are kept for Lullaby itself")
				if note then out[key] = note end
			else
				out[key] = copy(value)
			end
			depth = depth - 1
		end
	end
	on_path[t] = nil
	return out
end

-- One value as it goes into the save tree; nil (and a problem) if it can't be saved.
function copy(v)
	local t = type(v)
	if t == "number" then
		-- FORMAT: nan and inf can't be written so that they read back
		if v ~= v or v == math.huge or v == -math.huge then return problem("it is " .. I.number_text(v)
			.. ", which the file can't hold") end
		return v
	end
	if t == "string" or t == "boolean" then return v end
	if t == "cdata" and I.is_int64(v) then return v end
	if t ~= "table" then return problem(why_not(v)) end
	local kind = kind_of[v]
	if kind == "player" then return { [PREFIX .. "player"] = v.uuid } end
	if kind == "world" then return { [PREFIX .. "world"] = tostring(v) } end
	if kind then return problem(why_not(v)) end
	local meta = meta_of(v)
	if meta == nil then return copy_table(v) end
	local name = kind_by_meta[meta]
	if BY_CODEC[name] then return v end -- the wire tags it; Java saves it with vanilla's codec
	if BY_FIELDS[name] then
		local fields, out = value_fields[name], {}
		for i = 1, #fields do
			local field = rawget(v, i)
			if field ~= nil then out[fields[i]] = copy(field) end
		end
		return { [PREFIX .. name] = out }
	end
	return problem(why_not(v))
end

-- Java calls this to save: the save tree as wire bytes, and the problems (one text per line; Java shows the first 10).
function I.data_save()
	problems = {}
	depth = 0
	on_path = {}
	local tree = copy_table(data) or {}
	local found = problems
	problems = nil
	return I.wire(tree), concat(found, "\n")
end

-- ---------------------------------------------------------------------------------------------------------------
-- save tree -> Lua (loading)
-- ---------------------------------------------------------------------------------------------------------------

local load_problems

local function load_problem(why)
	-- FORMAT: one thing from server.snbt that can't be loaded
	load_problems[#load_problems + 1] = place() .. " can't be loaded: " .. why
	return nil
end

local revive

-- {"lullaby:<type>": inner} -> the Lullaby value; plain tables in place. nil (and a problem) if it can't be made.
local function revive_value(v)
	if type(v) ~= "table" then return v end
	local key, inner = raw_next(v)
	if type(key) == "string" and sub(key, 1, #PREFIX) == PREFIX and raw_next(v, key) == nil then
		local name = sub(key, #PREFIX + 1)
		-- Java checked these over the wire: a UUID, a slot number, a state number.
		if name == "player" then return I.player_by_uuid(inner) end
		if name == "item_stack" then return I.new_item(inner) end
		if name == "block_state" then return I.block_state_by_id(inner) end
		if name == "component" then return I.as_component(inner) end
		if name == "world" then
			if type(inner) ~= "string" then return load_problem("a world needs its id as text") end -- FORMAT
			local w = lib.world(inner)
			-- FORMAT: a saved world that isn't loaded now
			if not w then return load_problem("there is no world " .. inner .. " (not loaded, or gone)") end
			return w
		end
		if BY_FIELDS[name] then
			if type(inner) ~= "table" then return load_problem("a " .. name .. " needs its fields { }") end -- FORMAT
			local fields, args = value_fields[name], {}
			for i = 1, #fields do
				local field = rawget(inner, fields[i])
				if field ~= nil then
					depth = depth + 1; path[depth] = fields[i]
					field = revive_value(field)
					depth = depth - 1
					if field == nil then return nil end
				end
				args[i] = field
			end
			for field in raw_next, inner do
				local known = false
				for i = 1, #fields do if fields[i] == field then known = true end end
				if not known then return load_problem(name .. " has no field " .. tostring(field)) end -- FORMAT
			end
			local ok, made = pcall(lib[name], unpack(args, 1, #fields))
			if not ok then return load_problem(tostring(made)) end
			return made
		end
		return load_problem("Lullaby has no type " .. key) -- FORMAT: unknown "lullaby:..." type in the file
	end
	return revive(v)
end

-- A plain table from the file, its values made again in place. A list with a broken entry goes whole (see saving).
function revive(t)
	if #t > 0 then
		for i = 1, #t do
			depth = depth + 1; path[depth] = i
			local value = revive_value(rawget(t, i))
			depth = depth - 1
			if value == nil then
				whole_list(load_problems)
				return nil
			end
			t[i] = value
		end
		return t
	end
	local keys = {}
	for key in raw_next, t do keys[#keys + 1] = key end
	for i = 1, #keys do
		local key = keys[i]
		depth = depth + 1; path[depth] = key
		t[key] = revive_value(t[key])
		depth = depth - 1
	end
	return t
end

-- Java calls this before the scripts load: bytes = the saved tree (nil: nothing saved yet). Returns the problems,
-- one per line ("" if none); Java shows them and won't save over a file that didn't load cleanly.
function I.data_load(bytes)
	data = {}
	if bytes == nil then return "" end
	load_problems = {}
	depth = 0
	local tree = I.decode(bytes)
	if type(tree) == "table" then data = revive(tree) or {} end
	local found = load_problems
	load_problems = nil
	return concat(found, "\n")
end

-- ---------------------------------------------------------------------------------------------------------------
-- /lullaby data server (LullabyCommand.java): looking at the data as it is in memory now
-- ---------------------------------------------------------------------------------------------------------------

-- "stats.joins" -> the value there; nil and the place that is missing. A number part also finds a list entry.
local function at(text)
	local value = data
	depth = 0
	if text == "" then return value end
	for part in text:gmatch("[^.]+") do
		if type(value) ~= "table" or meta_of(value) ~= nil then return nil end
		local next_value = rawget(value, part)
		if next_value == nil and tonumber(part) then next_value = rawget(value, tonumber(part)) end
		depth = depth + 1; path[depth] = part
		value = next_value
		if value == nil then return nil end
	end
	return value
end

-- The view of one place as wire bytes (in save-tree shape, with notes where something won't be saved) and the
-- place's name; nil and the place's name if there is nothing.
function I.data_view(text)
	local value = at(text)
	local name = place()
	depth = 0
	if value == nil then return nil, name end
	on_path = {}
	problems = nil
	local tree = copy(value)
	return I.wire({ tree }), name
end

-- The keys at one place, one per line, sorted (for tab completion). Keys with a dot or a line break can't be typed
-- as a path, so they are left out.
function I.data_keys(text)
	local value = at(text)
	depth = 0
	if type(value) ~= "table" or meta_of(value) ~= nil then return "" end
	local keys = {}
	for key in raw_next, value do
		local k = type(key) == "number" and I.number_text(key) or type(key) == "string" and key or nil
		if k and not find(k, "[.\n]") then keys[#keys + 1] = k end
	end
	sort(keys)
	return concat(keys, "\n")
end

-- ---------------------------------------------------------------------------------------------------------------
-- server
-- ---------------------------------------------------------------------------------------------------------------

--[[doc
id: server
name: server
kind: type
group: Scripts
words: save saved data store persist keep flag variable reload restart
syntax: server.data.coins = 10
lead: The server itself. server.data is a table that stays: it is saved with the world (autosave, /save-all, server stop) and before /lullaby reload, and comes back after a reload or restart. Every script file sees the same server.data. Use it like any table: server.data.round = (server.data.round or 0) + 1. While the server runs it is a plain Lua table, so it costs nothing extra.
field: data | table | Saved data. Can be text, numbers, true/false, tables (lists, or text keys), players, worlds, positions and the other small values, location, item_stack, block_state and component. Other things (functions, entities, tasks, events, tables with their own metatable, nan and inf, tables more than 98 deep) are left out when saving, with a red error naming the place. Keys can't be empty text, and keys starting with "lullaby:" are kept for Lullaby. server.data = { } starts over.
note: The file is plugins/lullaby/data/server.snbt, readable text in vanilla's SNBT (what /data get shows). You may edit it: a change by hand wins, so Lullaby doesn't save over it and loads it at the next /lullaby reload (changes scripts made since the last save are then dropped, the console says so). /lullaby data server [path] shows the data, with tab completion of the keys. A table used in two places is saved twice and loads as two tables.
example:
  on{ "PlayerJoinEvent", function(e)
    local joins = server.data.joins or {}
    joins[e.player.uuid] = (joins[e.player.uuid] or 0) + 1
    server.data.joins = joins
  end }
expert: Java object | none while the server runs: server.data is a plain Lua table. Saving builds vanilla NBT once (nms/SavedData.java) and writes the file on a worker thread
expert: Stays valid | always; server.data survives /lullaby reload and restarts
expert: Compare | server is one object: server == server
expert: Cost | reading and writing server.data: as any Lua table, 1 000 000 writes took 0.5 ms (measured on the test server). Saving and loading: a /lullaby reload with a 4.6 MB file (200 000 values) took 966 ms, with small data 10–25 ms (measured); at an autosave the file is written and checked on a worker thread
status: unconfirmed
]]
local SERVER_META = {
	__index = function(_, key)
		if key == "data" then return data end
		I.no_field("server", key, "data", 2)
	end,
	__newindex = function(_, key, value)
		if key ~= "data" then error("server has no field " .. tostring(key) .. " to change (it has data)", 2) end -- FORMAT
		if type(value) ~= "table" or meta_of(value) ~= nil then
			error("server.data must be a table { }, got " .. I.type_name(value), 2) -- FORMAT
		end
		data = value
	end,
	__tostring = function() return "server" end,
	__metatable = false,
}
lib.server = setmetatable({}, SERVER_META)
kind_of[lib.server] = "server"

-- The data limit from the config (in megabytes; false: none), handed to Java once per load.
function I.data_setup(config)
	local limit = config.data_max_size
	if limit ~= false and (type(limit) ~= "number" or not (limit > 0) or limit == math.huge) then
		-- FORMAT: bad data_max_size in the config
		J.report("warning", "plugins/lullaby/config.lua", -1, "loading the config",
			"data_max_size must be false or a number of megabytes above 0 (using 16)")
		limit = 16
	end
	J.data_limit(limit == false and 0 or limit)
end
