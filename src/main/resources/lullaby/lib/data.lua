-- Lullaby library: saved data, which outlives /lullaby reload and restarts: server.data, w.data (one per world),
-- c.data (one per chunk) and entity.data (one per entity; player.data is the same field, a player is an entity).
--
-- While the server runs, every one of them is a PLAIN Lua table: reading and writing it costs what any Lua table
-- costs, no Java, no file. Only saving and loading turn it into something else:
--   save: save_tree walks the table once and builds a SAVE TREE (plain tables, text keys or lists only) that goes to
--     Java over the wire; Java turns it into vanilla NBT (nms/SavedData.java). What can't be saved (functions,
--     entities, tables with their own metatable, ...) is a red error naming its place (server.data.shop.on_buy) and
--     is left out; the rest is saved.
--   load: Java hands the tree back; load_tree turns it into Lua values again (players, worlds, item stacks, ...).
-- In the save tree a Lullaby value is a table with ONE key "lullaby:<type>" (in the file: {"lullaby:vec3": {x: 1, ...}}).
-- So scripts can't use keys starting with "lullaby:" in saved data: they are refused with a clear error.
--
-- Where each one lives (Java):
--   server.data  plugins/lullaby/data/server.snbt, saved with the main world (autosave, /save-all), before /lullaby
--                reload and at stop. Read before the scripts load.
--   w.data       the world's own folder, data/lullaby.snbt (it travels with the world folder), saved when that world
--                saves, before /lullaby reload and at stop. Read on the first w.data, only while the world is loaded.
--                When the world unloads, its table is LOCKED (emptied; any use is a red error that says why).
--   c.data       inside the chunk's own save (nms/ChunkData.java). Read on the first c.data, only while the chunk is
--                loaded. "Keep alive" (agreed with Kupfermeister 2026-11-23): when the chunk unloads, the table is
--                written into the chunk and then KEPT until the next reload, so `local d = c.data` never turns into a
--                copy that is no longer saved: at every save, a kept table that changed is written into its chunk
--                again (Java loads the chunk for that), and c.data gives the same table again when the chunk is back.
--                Kept until reload, not "while a script holds it" (a weak table): a script may change a table and
--                let go of it before the next save, or hold only a table inside it (local claims = c.data.claims);
--                a weak table would then be thrown away with the change, silently. Only if something else changed
--                the chunk's data meanwhile does the chunk's version win; the old table is then locked.
--   entity.data  inside the entity's own save (nms/EntityData.java); a player's in the player file, also while the
--                player is offline. Read on the first entity.data. An entity's table is locked when its chunk unloads
--                (an unloaded entity can't be used; reading entity.data again when it is back reads it again) or when
--                it dies or is removed. A player's table stays, online and offline (same object), until reload.
local J, lib, I = ...

local type, error, tostring, tonumber, rawget, rawset, pcall, unpack, setmetatable =
	type, error, tostring, tonumber, rawget, rawset, pcall, unpack, setmetatable
local raw_next = next
local find, sub, format, match, concat, sort = string.find, string.sub, string.format, string.match, table.concat,
	table.sort
local kind_of, kind_by_meta, meta_of, value_fields = I.kind_of, I.kind_by_meta, I.debug_getmetatable, I.value_fields
local slot_of = I.slot_of

local PREFIX = "lullaby:"
local WEAK_KEYS = { __mode = "k" }
local NOTHING = {}

-- Small values saved by their fields and made again with their maker (lib.<type>(field1, field2, ...)), which checks
-- what comes back from the file. Item stacks, text components and block states go through vanilla's codecs (Java).
local BY_FIELDS = { block_pos = true, section_pos = true, chunk_pos = true, vec3 = true, yaw_pitch = true,
	quaternion = true, aabb = true, bounding_box = true, global_pos = true, location = true }
local BY_CODEC = { item_stack = true, component = true, block_state = true }

local data = {} -- server.data

-- The names in errors: the script code that reaches the data (Java's own errors use the same, see ChunkData.name).
local function world_name(id) return "world(\"" .. id .. "\").data" end -- FORMAT
local function chunk_name(id, x, z) return "chunk(world(\"" .. id .. "\"), chunk_pos(" .. x .. ", " .. z .. ")).data" end -- FORMAT

-- ---------------------------------------------------------------------------------------------------------------
-- Where a problem is: the keys from the data's root down, kept as a stack (no text is built unless there is a problem)
-- ---------------------------------------------------------------------------------------------------------------

local root_name = "server.data" -- the data being saved, loaded or shown right now
local path, depth = {}, 0

local function key_text(key)
	if type(key) == "number" then return "[" .. I.number_text(key) .. "]" end
	if type(key) ~= "string" then return "[" .. tostring(key) .. "]" end -- true, a table, ... (not saved anyway)
	if find(key, "^[%a_][%w_]*$") then return "." .. key end
	return "[" .. (format("%q", key):gsub("\\\n", "\\n")) .. "]" -- %q keeps a line break as \ + break: one line
end

local function place()
	local parts = { root_name }
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
	-- FORMAT: one thing in saved data that can't be saved
	local text = place() .. " can't be saved: " .. why
	if problems then
		problems[#problems + 1] = text
		return nil
	end
	return "(not saved: " .. why .. ")" -- FORMAT: shown by /lullaby data in place of the value
end

local function copy_table(t)
	if on_path[t] then return problem("the table holds itself (a loop)") end
	-- Loading goes through the wire, which takes 100 levels counting the data's root and the last values: so a table
	-- may be at most 98 levels below the root.
	if depth > 98 then return problem("it is nested more than 98 tables deep inside " .. root_name) end -- FORMAT
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

-- One data table as its save tree and the problems (one text per line; Java shows the first 10).
local function save_tree(t, name)
	root_name = name
	problems = {}
	depth = 0
	on_path = {}
	local tree = copy_table(t) or {}
	local found = problems
	problems = nil
	return tree, concat(found, "\n")
end

-- Java calls this to save server.data: the save tree as wire bytes, and the problems.
function I.data_save()
	local tree, found = save_tree(data, "server.data")
	return I.wire(tree), found
end

-- ---------------------------------------------------------------------------------------------------------------
-- save tree -> Lua (loading)
-- ---------------------------------------------------------------------------------------------------------------

local load_problems

local function load_problem(why)
	-- FORMAT: one thing from saved data that can't be loaded
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

-- Wire bytes from Java (nil: nothing saved yet) -> the data table, and the problems, one per line ("" if none); Java
-- shows them and won't save over data that didn't load cleanly.
local function load_tree(bytes, name)
	if bytes == nil then return {}, "" end
	root_name = name
	load_problems = {}
	depth = 0
	local t = {}
	local tree = I.decode(bytes)
	if type(tree) == "table" then t = revive(tree) or {} end
	local found = load_problems
	load_problems = nil
	return t, concat(found, "\n")
end

-- Java calls this before the scripts load: bytes = the saved server.data (nil: nothing saved yet).
function I.data_load(bytes)
	local found
	data, found = load_tree(bytes, "server.data")
	return found
end

-- ---------------------------------------------------------------------------------------------------------------
-- Locked tables: data whose home is gone (its world unloaded, or the chunk's data was changed by something else)
-- ---------------------------------------------------------------------------------------------------------------

local locked_why = setmetatable({}, WEAK_KEYS) -- locked table -> why, said by every use
local LOCKED = {
	__index = function(t) error(locked_why[t], 2) end,
	__newindex = function(t) error(locked_why[t], 2) end,
	__metatable = false,
}

-- Empties the table and every plain table inside it and locks them, so a script that still holds one gets a red
-- error (with file and line) instead of writing into something that is no longer saved.
local function lock(t, why)
	local stack, seen = { t }, {}
	while #stack > 0 do
		local u = stack[#stack]
		stack[#stack] = nil
		if not seen[u] and meta_of(u) == nil then
			seen[u] = true
			local keys = {}
			for key, value in raw_next, u do
				keys[#keys + 1] = key
				if type(value) == "table" then stack[#stack + 1] = value end
			end
			for i = 1, #keys do rawset(u, keys[i], nil) end
			locked_why[u] = why
			setmetatable(u, LOCKED)
		end
	end
end

-- Lua checks only what Java can't see: that a new data table is a plain table.
local function check_table(name, value, level)
	if type(value) ~= "table" or meta_of(value) ~= nil then
		error(name .. " must be a table { }, got " .. I.type_name(value), level + 1) -- FORMAT
	end
end

-- ---------------------------------------------------------------------------------------------------------------
-- w.data
-- ---------------------------------------------------------------------------------------------------------------

local world_tables = {} -- world id -> its data table (read on the first w.data)

-- level: where an error points (1 = this function).
local function world_data(w, level)
	local id = tostring(w)
	local t = world_tables[id]
	if t then return t end
	local loaded, bytes = J.world_data_read(slot_of[w])
	-- FORMAT: w.data of a world that isn't loaded
	if not loaded then error("world " .. id .. " isn't loaded: its data can only be used while it is loaded", level + 1) end
	local found
	t, found = load_tree(bytes, world_name(id))
	J.world_data_loaded(slot_of[w], found)
	world_tables[id] = t
	return t
end

local WORLD_META = I.object_meta.world
WORLD_META.__index = function(w, key)
	if key == "data" then return world_data(w, 2) end
	I.no_field("world", key, "data; tostring gives its id", 2)
end
WORLD_META.__newindex = function(w, key, value)
	if key ~= "data" then error("world has no field " .. tostring(key) .. " to change (it has data)", 2) end -- FORMAT
	check_table("w.data", value, 2)
	world_data(w, 2) -- read first: Java then knows the world's data is in use, and a file that didn't load stays safe
	world_tables[tostring(w)] = value
end

-- Java calls this when the world saves: its save tree as wire bytes and the problems; nil if no script read w.data.
function I.world_data_save(id)
	local t = world_tables[id]
	if t == nil then return nil, "" end
	local tree, found = save_tree(t, world_name(id))
	return I.wire(tree), found
end

-- ---------------------------------------------------------------------------------------------------------------
-- c.data
-- ---------------------------------------------------------------------------------------------------------------

local attached = {} -- world id -> { ["x|z"] = table } of loaded chunks (Lua holds them)
local kept = {}     -- world id -> { ["x|z"] = table } of unloaded chunks read since the last reload (see the top)

local function attach(id, xz, t)
	local here = attached[id]
	if not here then here = {}; attached[id] = here end
	here[xz] = t
end

-- c.data; level: where an error points (1 = this function).
function I.chunk_data(c, level)
	local id, x, z = I.chunk_parts(c)
	local xz = x .. "|" .. z
	local here = attached[id]
	local t = here and here[xz]
	if t then return t end
	local slot = slot_of[I.world_by_id(id)]
	local keep = kept[id]
	t = keep and keep[xz]
	if t then
		local state = J.chunk_data_reattach(slot, x, z)
		if state == 0 then return t end -- still unloaded: changes reach the chunk at the next save
		keep[xz] = nil
		if state == 1 then
			attach(id, xz, t)
			return t
		end
		-- FORMAT: a kept chunk table whose chunk was changed by something else
		lock(t, "this was " .. chunk_name(id, x, z) .. ", but something else changed the chunk's data while it was"
			.. " unloaded, and the chunk's version counts: read c.data again")
	end
	local loaded, bytes = J.chunk_data_read(slot, x, z)
	if not loaded then
		-- FORMAT: c.data of a chunk that isn't loaded
		error("chunk " .. x .. " " .. z .. " in " .. id .. " isn't loaded: its data can only be read while it is"
			.. " loaded (check c.loaded first)", level + 1)
	end
	local found
	t, found = load_tree(bytes, chunk_name(id, x, z))
	J.chunk_data_loaded(slot, x, z, found)
	attach(id, xz, t)
	return t
end

-- c.data = { }: starts over; only while the chunk is loaded.
function I.set_chunk_data(c, value, level)
	check_table("c.data", value, level + 1)
	I.chunk_data(c, level + 1)
	local id, x, z = I.chunk_parts(c)
	local here = attached[id]
	local xz = x .. "|" .. z
	if not (here and here[xz]) then
		-- FORMAT: c.data = ... while the chunk is unloaded
		error("chunk " .. x .. " " .. z .. " in " .. id .. " isn't loaded: c.data = { } needs it loaded", level + 1)
	end
	here[xz] = value
end

-- Java calls this when an attached chunk unloads: its save tree as wire bytes and the problems. The table is kept as
-- long as some script holds it.
function I.chunk_data_unload(id, x, z)
	local xz = x .. "|" .. z
	local here = attached[id]
	local t = here and here[xz]
	if not t then return I.wire(NOTHING), "" end
	here[xz] = nil
	local keep = kept[id]
	if not keep then keep = {}; kept[id] = keep end
	keep[xz] = t
	local tree, found = save_tree(t, chunk_name(id, x, z))
	return I.wire(tree), found
end

-- A save of one world: Java first asks how many chunk tables there are (attached, then kept ones), then
-- takes them one by one (I.chunk_data_entry), so each chunk's data has the wire's full depth.
local entries, entries_world = {}, nil
local function add_entries(list, is_kept)
	for xz, t in raw_next, list do
		local x, z = match(xz, "^(-?%d+)|(-?%d+)$")
		entries[#entries + 1] = { tonumber(x), tonumber(z), is_kept, t }
	end
end
function I.chunk_data_save(id)
	entries, entries_world = {}, id
	add_entries(attached[id] or NOTHING, false)
	add_entries(kept[id] or NOTHING, true)
	return #entries
end

-- Entry i of the last I.chunk_data_save: x, z, kept, the save tree as wire bytes, the problems.
function I.chunk_data_entry(i)
	local e = entries[i]
	if i == #entries then entries = {} end -- the last one: nothing is held longer than the save
	local tree, found = save_tree(e[4], chunk_name(entries_world, e[1], e[2]))
	return e[1], e[2], e[3], I.wire(tree), found
end

-- Java calls this the tick after a world unloaded: its w.data and every c.data of it are locked.
function I.world_gone(id)
	local t = world_tables[id]
	world_tables[id] = nil
	-- FORMAT: w.data after its world unloaded
	if t then lock(t, "this is " .. world_name(id) .. ", and that world was unloaded: load it and read w.data again") end
	for _, list in raw_next, { attached[id] or NOTHING, kept[id] or NOTHING } do
		for xz, c in raw_next, list do
			local x, z = match(xz, "^(-?%d+)|(-?%d+)$")
			-- FORMAT: c.data after its world unloaded
			lock(c, "this is " .. chunk_name(id, x, z) .. ", and its world was unloaded: load it and read c.data again")
		end
	end
	attached[id] = nil
	kept[id] = nil
end

-- ---------------------------------------------------------------------------------------------------------------
-- entity.data and player.data (a player is an entity: the same field; nms/EntityData.java says where each is kept)
-- ---------------------------------------------------------------------------------------------------------------

local entity_tables = {} -- uuid text -> its data table (read on the first entity.data); one per UUID, players too

-- The name in errors: the script code that reaches the data (Java uses the one Lua hands it on the read).
local function entity_data_name(o, is_player) -- FORMAT
	if is_player then return "player(\"" .. (o.name or o.uuid) .. "\").data" end
	return "entity(\"" .. o.uuid .. "\").data"
end

-- FORMAT: why a data table of an entity or player is locked (Java names the reason)
local GONE = {
	unloaded = "and its chunk unloaded: read entity.data again when it is loaded (check entity.alive first)",
	removed = "and that entity is gone (it died or was removed): its data went with it",
	changed = "but something else changed the player file while the player was offline, and the file's version counts:"
		.. " read player.data again",
}

-- entity.data / player.data; level: where an error points (1 = this function).
function I.entity_data(o, is_player, level)
	local uuid = o.uuid
	local t = entity_tables[uuid]
	if t then return t end
	local name = entity_data_name(o, is_player)
	local state, bytes = J.entity_data_read(uuid, is_player, name)
	if state == 0 then
		-- FORMAT: entity.data of an entity that isn't loaded
		error("entity " .. uuid .. " isn't loaded: its data can only be used while it is (check entity.alive first)",
			level + 1)
	end
	if state == 2 then
		-- FORMAT: player.data of an offline player without a player file
		error(tostring(o) .. " has no player file yet (never joined this server): its data can be used once they"
			.. " have joined", level + 1)
	end
	local found
	t, found = load_tree(bytes, name)
	J.entity_data_loaded(uuid, found)
	entity_tables[uuid] = t
	return t
end

-- entity.data = { }: starts over (read first: Java then knows the data is in use, and data that didn't load stays safe).
function I.set_entity_data(o, is_player, value, level)
	check_table(is_player and "player.data" or "entity.data", value, level + 1)
	I.entity_data(o, is_player, level + 1)
	entity_tables[o.uuid] = value
end

-- Java calls this to write one table: its save tree as wire bytes and the problems; nil if Lua has no table.
function I.entity_data_tree(uuid, name)
	local t = entity_tables[uuid]
	if t == nil then return nil, "" end
	local tree, found = save_tree(t, name)
	return I.wire(tree), found
end

-- Java calls this when the table's home is gone: the table is locked, the next entity.data reads again.
function I.entity_data_gone(uuid, name, reason)
	local t = entity_tables[uuid]
	if t == nil then return end
	entity_tables[uuid] = nil
	lock(t, "this was " .. name .. ", " .. GONE[reason])
end

-- ---------------------------------------------------------------------------------------------------------------
-- /lullaby data (LullabyCommand.java): looking at the data as it is in memory now
-- ---------------------------------------------------------------------------------------------------------------

-- Java names the data: "server", "world <id>" or "chunk <id> <x> <z>". Gives the table and its name.
local function target(which)
	if which == "server" then return data, "server.data" end
	local kind, id, x, z = match(which, "^(%a+) (%S+) ?(%S*) ?(%S*)$")
	if kind == "world" then return world_data(I.world_by_id(id), 2), world_name(id) end
	if kind == "chunk" then
		x, z = tonumber(x), tonumber(z)
		return I.chunk_data(I.chunk_at(id, x, z), 2), chunk_name(id, x, z)
	end
	error("Lullaby bug: unknown saved data " .. which, 0)
end

-- "stats.joins" -> the value there; nil if nothing is there. A number part also finds a list entry.
local function at(root, text)
	local value = root
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
function I.data_view(which, text)
	local root, name = target(which)
	root_name = name
	local value = at(root, text)
	local shown = place()
	depth = 0
	if value == nil then return nil, shown end
	on_path = {}
	problems = nil
	local tree = copy(value)
	return I.wire({ tree }), shown
end

-- The keys at one place, one per line, sorted (for tab completion). Keys with a dot or a line break can't be typed
-- as a path, so they are left out.
function I.data_keys(which, text)
	local root = target(which)
	local value = at(root, text)
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
lead: The server itself. server.data is a table that stays: it is saved with the world (autosave, /save-all, server stop) and before /lullaby reload, and comes back after a reload or restart. Every script file sees the same server.data. Use it like any table: server.data.round = (server.data.round or 0) + 1. While the server runs it is a plain Lua table, so it costs nothing extra. Worlds, chunks, entities and players have their own: world.data (see world( )), context.data (see chunk( )), entity.data (see entity( )) and player.data (see player( )).
field: data | table | Saved data. Can be text, numbers, true/false, tables (lists, or text keys), players, worlds, positions and the other small values, location, item_stack, block_state and component. Other things (functions, entities, tasks, events, tables with their own metatable, nan and inf, tables more than 98 deep) are left out when saving, with a red error naming the place. Keys can't be empty text, and keys starting with "lullaby:" are kept for Lullaby. server.data = { } starts over.
note: The file is plugins/lullaby/data/server.snbt, readable text in vanilla's SNBT (what /data get shows). You may edit it: a change by hand wins, so Lullaby doesn't save over it and loads it at the next /lullaby reload (changes scripts made since the last save are then dropped, the console says so). /lullaby data server [path] shows the data, with tab completion of the keys. A table used in two places is saved twice and loads as two tables.
example:
  on{ "PlayerJoinEvent", function(event)
    local joins = server.data.joins or {}
    joins[event.player.uuid] = (joins[event.player.uuid] or 0) + 1
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
		check_table("server.data", value, 2)
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
