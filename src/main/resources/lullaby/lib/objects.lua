-- Lullaby library, file 6 of 9: game objects, block states, and the first action.
--
-- Objects (player, world, block) are hand-made: each one exists once (cached), so == works and they can be table
-- keys. Their identity (a UUID, an id, a position) is kept in private tables, never on the object, and every field
-- is read from Java when asked. Fields and methods are added only with Kupfermeister's yes.
--   player("Notch") / player(uuid text)  -> player or nil (online, or has played here)     p.name  p.uuid  p.online
--   world("overworld")                   -> world ("minecraft:overworld") or nil
--   block (a PLACE: world + position)    b.state (block_state, nil if the chunk is not loaded)  b.pos  b.world
--   block_state("oak_stairs[facing=north]") -> block_state (exists once per state, so == is free)
--                                        s.type (id)  s.<property> (s.facing)  s:with{ facing = "south" }
--   message{ player = p, text = "<red>Hi" }   chat message (MiniMessage text or a component) -> true, false if p is offline
local J, lib, I = ...

local type, error, setmetatable, tostring = type, error, setmetatable, tostring

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of
I.object_meta = {} -- kind -> metatable of player, world, block objects (the wire tags them, see lazy.lua) -- object -> "player", "world", "block" (core.lua)
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

-- "player", "world", "block" for Lullaby objects, nil for anything else.
function I.object_kind(value)
	return kind_of[value]
end

local function read_only(kind)
	return function(_, key) error(kind .. " fields can't be changed (." .. tostring(key) .. ")", 2) end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Players
-- ---------------------------------------------------------------------------------------------------------------

local player_uuid = setmetatable({}, WEAK_KEYS) -- player -> uuid text
local players = setmetatable({}, WEAK_VALUES)   -- uuid text -> player

local PLAYER_META = {
	__index = function(p, key)
		local uuid = player_uuid[p]
		if key == "uuid" then return uuid end
		if key == "name" then return J.player_name(slot_of[p]) end
		if key == "online" then return J.player_online(slot_of[p]) end
		return nil
	end,
	__newindex = read_only("player"),
	__tostring = function(p) return "player " .. (J.player_name(slot_of[p]) or player_uuid[p]) end, -- FORMAT
	__metatable = false,
}
I.object_meta.player = PLAYER_META

function I.player_by_uuid(uuid)
	local p = players[uuid]
	if not p then
		p = setmetatable({}, PLAYER_META)
		player_uuid[p] = uuid
		kind_of[p] = "player"
		I.give_slot(p, J.player_slot(uuid))
		players[uuid] = p
	end
	return p
end

function lib.player(x)
	if player_uuid[x] then return x end
	if type(x) ~= "string" then error("player: needs a name or UUID text, got " .. I.type_name(x), 2) end
	local uuid = J.player_find(x)
	return uuid and I.player_by_uuid(uuid) or nil
end

-- ---------------------------------------------------------------------------------------------------------------
-- Materials and worlds (vanilla ids; "stone" means "minecraft:stone")
-- ---------------------------------------------------------------------------------------------------------------

-- make_slot (optional): Java function that gives an object its slot from its id (worlds; see Slots.java).
local function id_objects(kind, find, make_slot)
	local id_of = setmetatable({}, WEAK_KEYS)
	local by_id = {} -- strong: there are only a few hundred, and the same id must always give the same object
	local meta = {
		__index = function() return nil end,
		__newindex = read_only(kind),
		__tostring = function(o) return id_of[o] end,
		__metatable = false,
	}
	I.object_meta[kind] = meta
	local function by(id)
		local o = by_id[id]
		if not o then
			o = setmetatable({}, meta)
			id_of[o] = id
			kind_of[o] = kind
			if make_slot then I.give_slot(o, make_slot(id)) end
			by_id[id] = o
		end
		return o
	end
	local function public(x)
		if id_of[x] then return x end
		if type(x) ~= "string" then error(kind .. ": needs an id text like \"stone\", got " .. I.type_name(x), 2) end
		local id = find(x)
		return id and by(id) or nil
	end
	return by, public, id_of
end

local world_id_of
I.world_by_id, lib.world, world_id_of = id_objects("world", J.world_id, J.world_slot)

-- ---------------------------------------------------------------------------------------------------------------
-- Block states: vanilla keeps every state once and numbers them; Lua keeps one table per number in use.
-- ---------------------------------------------------------------------------------------------------------------

local states = setmetatable({}, WEAK_VALUES)  -- vanilla state number -> block_state (freed when unused)
local state_id = setmetatable({}, WEAK_KEYS)  -- block_state -> its number
local state_info = setmetatable({}, WEAK_KEYS) -- block_state -> { type = id, props = { name -> value }, kinds }
local state_methods = {}

-- Java answers "type" then one line per property: name TAB kind (b = true/false, i = whole number, e = word)
-- TAB value.
local function info_of(s)
	local info = state_info[s]
	if info then return info end
	local text = J.block_state_info(state_id[s])
	local props, kinds = {}, {}
	local type_id
	for line in text:gmatch("[^\n]+") do
		if not type_id then
			type_id = line
		else
			local name, kind, value = line:match("^([^\t]+)\t(.)\t(.*)$")
			kinds[name] = kind
			if kind == "b" then props[name] = value == "true"
			elseif kind == "i" then props[name] = tonumber(value)
			else props[name] = value end
		end
	end
	info = { type = type_id, props = props, kinds = kinds }
	state_info[s] = info
	return info
end

local STATE = {
	__index = function(s, key)
		local info = info_of(s)
		if key == "type" then return info.type end
		local value = info.props[key]
		if value ~= nil then return value end
		return state_methods[key]
	end,
	__newindex = function(_, key)
		error("block_state values can't be changed (." .. tostring(key) .. "): use s:with{ ... }", 0)
	end,
	__tostring = function(s)
		local info = info_of(s)
		if not info.text then info.text = J.block_state_text(state_id[s]) end
		return info.text
	end,
	__metatable = false,
}
I.kind_by_meta[STATE] = "block_state"

function I.block_state_by_id(id)
	local s = states[id]
	if not s then
		s = setmetatable({}, STATE)
		state_id[s] = id
		states[id] = s
	end
	return s
end

-- block_state("oak_stairs[facing=north]"): vanilla's own text (missing properties take their default).
function lib.block_state(x)
	if state_id[x] then return x end
	if type(x) ~= "string" then error("block_state needs text like \"oak_stairs[facing=north]\", got " .. I.type_name(x), 0) end
	return I.block_state_by_id(J.block_state_parse(x))
end

local KIND_NAME = { b = "true or false", i = "a whole number", e = "a word" }
-- s:with{ facing = "south" }: the state with these properties changed (vanilla's setValue); s itself never changes.
function state_methods.with(s, changes)
	if not state_id[s] then error("block_state:with needs a block_state (use : not .)", 0) end
	if type(changes) ~= "table" then error("with needs { }: s:with{ facing = \"south\" }", 0) end
	local info = info_of(s)
	local lines, n = {}, 0
	for name, value in pairs(changes) do
		local kind = type(name) == "string" and info.kinds[name]
		if not kind then error(info.type .. " has no property " .. tostring(name), 0) end
		local text
		if kind == "b" and type(value) == "boolean" then text = tostring(value)
		elseif kind == "i" and type(value) == "number" and value == math.floor(value) then text = I.number_text(value)
		elseif kind == "e" and type(value) == "string" then text = value
		else
			error(info.type .. ": " .. name .. " must be " .. KIND_NAME[kind] .. ", got " .. I.type_name(value), 0)
		end
		n = n + 1
		lines[n] = name .. "\t" .. text
	end
	if n == 0 then return s end
	return I.block_state_by_id(J.block_state_with(state_id[s], table.concat(lines, "\n")))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Blocks (places)
-- ---------------------------------------------------------------------------------------------------------------

local block_pos = setmetatable({}, WEAK_KEYS) -- block -> { world id, x, y, z }
local blocks = setmetatable({}, WEAK_VALUES)  -- "world|x|y|z" -> block

local BLOCK_META = {
	__index = function(b, key)
		local pos = block_pos[b]
		if key == "state" then
			local id = J.block_state_at(slot_of[I.world_by_id(pos[1])], pos[2], pos[3], pos[4])
			return id and I.block_state_by_id(id) or nil
		end
		if key == "pos" then return I.new_block_pos(pos[2], pos[3], pos[4]) end
		if key == "world" then return I.world_by_id(pos[1]) end
		return nil
	end,
	__newindex = read_only("block"),
	__tostring = function(b) local p = block_pos[b] return "block " .. p[2] .. " " .. p[3] .. " " .. p[4] .. " in " .. p[1] end, -- FORMAT
	__metatable = false,
}
I.object_meta.block = BLOCK_META

-- world: the world's full id; x, y, z: whole numbers in int range (checked by the caller).
function I.block_at(world, x, y, z)
	local key = world .. "|" .. x .. "|" .. y .. "|" .. z
	local b = blocks[key]
	if not b then
		b = setmetatable({}, BLOCK_META)
		block_pos[b] = { world, x, y, z }
		kind_of[b] = "block"
		blocks[key] = b
	end
	return b
end

-- ---------------------------------------------------------------------------------------------------------------
-- Actions
-- ---------------------------------------------------------------------------------------------------------------

function lib.message(a)
	if type(a) ~= "table" then error("message needs { }: message{ player = p, text = \"...\" }", 2) end
	if kind_of[a.player] ~= "player" then error("message: player must be a player, got " .. I.type_name(a.player), 2) end
	if I.type_name(a.text) == "component" then return J.message_component(slot_of[a.player], slot_of[a.text]) end
	if type(a.text) ~= "string" then error("message: text must be text or a component, got " .. I.type_name(a.text), 2) end
	return J.message(slot_of[a.player], a.text)
end
