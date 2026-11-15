-- Lullaby library, file 5 of 7: game objects and values, and the first action.
--
-- Objects (player, material, world, block) are hand-made: each one exists once (cached), so == works and they can be
-- table keys. Their identity (a UUID, an id like "minecraft:stone", a position) is kept in private tables, never on
-- the object, and every field is read from Java when asked. Fields and methods are added only with Kupfermeister's yes.
--   player("Notch") / player(uuid text)  -> player or nil (online, or has played here)     p.name  p.uuid  p.online
--   material("stone")                    -> material (a block type, "minecraft:stone") or nil
--   world("overworld")                   -> world ("minecraft:overworld") or nil
--   block (from events)                  b.type (material, nil if the chunk is not loaded)  b.location
--   location value                       x  y  z  world (a plain Lua value: copies, == compares the contents)
--   message{ player = p, text = "<red>Hi" }   chat message (MiniMessage text) -> true, false if p is offline
local J, lib, I = ...

local type, error, setmetatable, tostring = type, error, setmetatable, tostring

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of -- object -> "player", "material", "world", "block", "location" (core.lua)
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

-- "player", "material", ... for Lullaby objects, nil for anything else.
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
I.material_by_id, lib.material = id_objects("material", J.material_id)
I.world_by_id, lib.world, world_id_of = id_objects("world", J.world_id, J.world_slot)

-- ---------------------------------------------------------------------------------------------------------------
-- Locations (plain values) and blocks
-- ---------------------------------------------------------------------------------------------------------------

local LOCATION_META = {
	__eq = function(a, b) return a.x == b.x and a.y == b.y and a.z == b.z and a.world == b.world end,
	__tostring = function(l) return l.x .. ", " .. l.y .. ", " .. l.z .. " in " .. tostring(l.world) end, -- FORMAT
	__metatable = false,
}

function I.location(x, y, z, world)
	local l = setmetatable({ x = x, y = y, z = z, world = world }, LOCATION_META)
	kind_of[l] = "location"
	return l
end

local block_pos = setmetatable({}, WEAK_KEYS) -- block -> { world id, x, y, z }
local blocks = setmetatable({}, WEAK_VALUES)  -- "world|x|y|z" -> block

local BLOCK_META = {
	__index = function(b, key)
		local pos = block_pos[b]
		if key == "type" then
			local id = J.block_type(slot_of[I.world_by_id(pos[1])], pos[2], pos[3], pos[4])
			return id and I.material_by_id(id) or nil
		end
		if key == "location" then return I.location(pos[2], pos[3], pos[4], I.world_by_id(pos[1])) end
		return nil
	end,
	__newindex = read_only("block"),
	__tostring = function(b) local p = block_pos[b] return "block " .. p[2] .. ", " .. p[3] .. ", " .. p[4] .. " in " .. p[1] end, -- FORMAT
	__metatable = false,
}

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
	if type(a.text) ~= "string" then error("message: text must be text, got " .. I.type_name(a.text), 2) end
	return J.message(slot_of[a.player], a.text)
end
