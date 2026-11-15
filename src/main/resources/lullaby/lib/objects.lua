-- Lullaby library: game objects, block states, and the first action.
--
-- Objects (player, entity, world, block, chunk) are hand-made: each one exists once (cached), so == works and they
-- can be table keys. Their identity (a UUID, an id, a position) is kept in private tables, never on the object, and
-- every field is read from Java when asked. Reading a name an object doesn't have is an error (I.no_field), never
-- nil. Fields and methods are added only with Kupfermeister's yes.
--   player("Notch") / player(uuid text)  -> player or nil (online, or has played here)     p.name  p.uuid  p.online,
--                                           and every entity field (a player is an entity; offline they read nil)
--   entity(uuid text)                    -> entity (or player) or nil if none is loaded    e.uuid e.type e.pos ... (the
--                                           field list is Java's: nms/Entities.java)
--   set{ entity = e, location = , velocity = , health = }   changes an entity -> true, false if it is gone
--   world("overworld")                   -> world ("minecraft:overworld") or nil
--   chunk(world, chunk_pos) / location:chunk()  -> chunk (a PLACE: world + chunk_pos)  c.world c.pos c.loaded c.entities
--   damage (from events: e.damage_source)  d.type  d.direct  d.causing  d.pos
--   block (a PLACE: world + position)    b.state (block_state, nil if the chunk is not loaded)  b.pos  b.world
--   block_state("oak_stairs[facing=north]") / block_state{ id = "oak_stairs", facing = "north" } /
--   block_state{ id = "oak_stairs", properties = { facing = "north" } } (vanilla's codec shape) -> block_state (exists once per state, so == is free)
--                                        s.type (id)  s.<property> (s.facing)  s:with{ facing = "south" }
--   message{ player = p, text = "Hi" | component("<red>Hi") | { text = "Hi", color = "red" } }   chat message (a string
--       is literal text, as /tellraw "Hi"; a table is a component) -> true, false if p is offline
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber, raw_next = type, error, setmetatable, tostring, tonumber, next
local gmatch, concat = string.gmatch, table.concat
local debug_getmetatable = I.debug_getmetatable

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of
I.object_meta = {} -- kind -> metatable of each object kind (the wire tags them, see lazy.lua)
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

-- "player", "entity", "world", "block", "chunk" for Lullaby objects, nil for anything else.
function I.object_kind(value)
	return kind_of[value]
end

local function read_only(kind)
	return function(_, key) error(kind .. " fields can't be changed (." .. tostring(key) .. ")", 2) end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Players
-- ---------------------------------------------------------------------------------------------------------------

-- Entity fields: Java owns the list (Entities.java); names cross once, here, and every read sends only a number.
-- uuid is answered in Lua (it is the object's identity).
local ENTITY_FIELDS, entity_names = {}, {}
for name, number in gmatch(J.entity_fields(), "([%w_]+)=(%d+)") do
	ENTITY_FIELDS[name] = tonumber(number)
	entity_names[#entity_names + 1] = name
end
local ENTITY_HAS = concat(entity_names, ", ")
local PLAYER_HAS = "name, online, " .. ENTITY_HAS

-- level 3: the script line that read the field (entity_field <- __index <- script)
local function entity_field(o, key, kind, has)
	local number = ENTITY_FIELDS[key]
	if not number then I.no_field(kind, key, has, 3) end
	return I.from_java(J.entity_get(slot_of[o], number))
end

-- Entities and players change only through actions (set{ } uses the field names): e.velocity = v is an error.
local function no_assign(letter)
	return function(_, key)
		-- FORMAT: an entity or player field assigned with = instead of an action
		error("entity fields can't be changed with = (." .. tostring(key) .. "): use an action, like set{ entity = "
			.. letter .. ", " .. tostring(key) .. " = ... }", 2)
	end
end

local player_uuid = setmetatable({}, WEAK_KEYS) -- player -> uuid text
local players = setmetatable({}, WEAK_VALUES)   -- uuid text -> player

local PLAYER_META = {
	__index = function(p, key)
		if key == "uuid" then return player_uuid[p] end
		if key == "name" then return J.player_name(slot_of[p]) end
		if key == "online" then return J.player_online(slot_of[p]) end
		return entity_field(p, key, "player", PLAYER_HAS) -- a player is an entity too (nil while offline)
	end,
	__newindex = no_assign("p"),
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

--[[doc
name: player( )
kind: reading
group: Players
words: get find user name uuid
syntax: player( text ) → player | nil
lead: A player by name or UUID: online, or one who has played on this server before. nil if none. Each player exists once, so == works, online and offline. A player is an entity too: every field of entity( ) works on p (p.pos, p.health, ...) and reads nil while the player is offline; set{ entity = p, ... } changes an online player.
arg: x | text · player | Name or UUID (a player is returned as is).
returns: player · nil
field: uuid | text | The UUID, lowercase with dashes. Also while offline.
field: name | text · nil | The name; for an offline player the one the server last saw (nil if it never saw one).
field: online | boolean | Whether the player is online now.
expert: Thread | main thread, like every script
expert: Cost | online: 1–5 µs; an offline name may read the server's player cache: 5 µs–5 ms (guess, not measured)
expert: Under the hood | Nms.findPlayer: online players first, then players who played here before
expert: Can fail | x not text or a player; an unknown name gives nil, no error
expert: Reload | player objects are made new after reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.player(x)
	if player_uuid[x] then return x end
	if type(x) ~= "string" then error("player: needs a name or UUID text, got " .. I.type_name(x), 2) end
	local uuid = J.player_find(x)
	return uuid and I.player_by_uuid(uuid) or nil
end

-- ---------------------------------------------------------------------------------------------------------------
-- Entities (every entity that is not a player; players above are entities too)
-- ---------------------------------------------------------------------------------------------------------------

local entity_uuid = setmetatable({}, WEAK_KEYS) -- entity -> uuid text
local entities = setmetatable({}, WEAK_VALUES)  -- uuid text -> entity

local ENTITY_META = {
	__index = function(e, key)
		if key == "uuid" then return entity_uuid[e] end
		return entity_field(e, key, "entity", ENTITY_HAS)
	end,
	__newindex = no_assign("e"),
	-- FORMAT: an entity as text: "entity zombie <uuid>", "(gone)" when it is not in the world
	__tostring = function(e)
		local t = e.type
		return "entity " .. (t and (t:gsub("^minecraft:", "")) or "(gone)") .. " " .. entity_uuid[e]
	end,
	__metatable = false,
}
I.object_meta.entity = ENTITY_META

-- Java hands an entity over as its UUID text plus a NEW slot holding it (ToLua.java): the slot keeps the very Java
-- entity even while a spawn event runs, before vanilla can find it by UUID. An entity Lua already has keeps its own
-- slot, and the new one is handed back at once.
function I.entity_from_java(uuid, slot)
	local e = entities[uuid]
	if e then
		I.free_slot(slot)
		return e
	end
	e = setmetatable({}, ENTITY_META)
	entity_uuid[e] = uuid
	kind_of[e] = "entity"
	I.give_slot(e, slot)
	entities[uuid] = e
	return e
end

-- An entity inside another value (a damage's direct and causing): uuid and slot, slot 0 for a player, nil for none.
local function nested_entity(uuid, slot)
	if uuid == nil then return nil end
	if slot == 0 then return I.player_by_uuid(uuid) end
	return I.entity_from_java(uuid, slot)
end

--[[doc
name: entity( )
kind: reading
group: Entities
words: mob creature animal item uuid find living
fields-from: src/main/java/io/github/kupfermeister/lullaby/nms/Entities.java Field
syntax: entity( uuid ) → entity | player | nil
lead: An entity by its UUID, if it is in a loaded chunk right now: a mob, an item on the ground, an arrow, a minecart, ... (an online player gives the player). nil if there is none. Every sort of entity is one entity: a field a sort doesn't have reads nil (an item has no health). Each entity exists once, so == works, and it stays the same object when its chunk unloads and loads again or it changes world. Entities also come from events (e.entity, e.damager, ...) and from chunks (c.entities). Change one with set{ }.
arg: x | text · entity | A UUID like "069a79f4-44e9-4726-a5be-fca90e38aaf5" (an entity or player is returned as is).
returns: entity · player · nil
field: uuid | text | The UUID, lowercase with dashes. Also when the entity is gone.
field: type | text | The sort, as vanilla's id: "minecraft:zombie", "minecraft:item".
field: alive | boolean | false once it is dead or gone (removed, in an unloaded chunk, offline).
field: world | world | The world it is in.
field: pos | vec3 | Where its feet are.
field: location | location | World, position and direction (yaw, pitch) at once.
field: velocity | vec3 | Its motion in blocks per tick (vanilla's delta movement). set{ } changes it.
field: on_ground | boolean | Whether it stands on something.
field: health | number · nil | Health points (2 per heart); nil for entities without health (items, arrows, minecarts, ...). set{ } changes it.
field: max_health | number · nil | Its highest health; nil for entities without health.
field: custom_name | component · nil | The name given with a name tag or a command; nil if it has none.
field: vehicle | entity · player · nil | What it rides (a boat, a horse, ...); nil if nothing.
note: A gone entity (dead, removed, in an unloaded chunk) reads nil everywhere except uuid and alive (false); it comes back by itself when its chunk loads again. Fields are read from the game each time: keep the value (local p = e.pos) when you need it twice. A name an entity doesn't have at all (e.helth) is an error that lists the fields.
example:
  local e = entity("069a79f4-44e9-4726-a5be-fca90e38aaf5")
  if e and e.alive then print(e.type, e.pos, e.health) end
expert: Thread | main thread, like every script
expert: Cost | entity( ): 1–10 µs; a field: 1–5 µs, a Java call each read (guess, not measured)
expert: Under the hood | nms/Entities.java: the slot keeps the vanilla entity and its UUID; once that entity is removed (unloaded, changed world) the UUID is looked up again in every world (vanilla's LevelEntityGetter), never loading a chunk
expert: Can fail | x not text or an entity; a field it doesn't have (the error lists them); an unknown UUID gives nil
expert: Reload | entity objects are made new after reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.entity(x)
	local kind = kind_of[x]
	if kind == "entity" or kind == "player" then return x end
	-- FORMAT: entity( ) of something that isn't text
	if type(x) ~= "string" then error("entity: needs a UUID text, got " .. I.type_name(x), 2) end
	return I.from_java(J.entity_find(x))
end

-- set{ } takes the fields Java lists as changeable; Java checks the names and values (Entities.java). Lua checks only
-- what Java can't see: the entity object, and a location's world (an object's identity doesn't cross the wire).
--[[doc
name: set{ }
kind: doing
group: Entities
words: change teleport tp move velocity motion push launch knockback health heal
syntax: set{ entity = entity | player, location = location, velocity = vec3, health = number }
lead: Changes an entity or an online player. Give the entity and one or more of the fields below: the same names as the fields you read (e.velocity, e.health, e.location). Every value is checked before anything changes.
arg: entity | entity · player | What to change.
arg: location? | location | Teleports it there, as /tp does (also into another world): its fall speed is reset unless it is gliding, and a mob stops walking its path.
arg: velocity? | vec3 | Its new motion in blocks per tick (1 block per tick is 20 blocks per second). Applied after location.
arg: health? | number | Its new health, kept between 0 and its max_health as vanilla does; 0 kills it. Only for entities with health.
returns: boolean (false if the entity is gone or the teleport was refused, for example by another plugin)
example:
  -- throw e up and towards p
  set{ entity = e, velocity = (p.pos - e.pos):normalize() * 0.5 + vec3(0, 0.5, 0) }
expert: Thread | main thread, like every script
expert: Cost | 5–30 µs, a teleport into another world more (guess, not measured)
expert: Under the hood | one Lua → Java call; velocity: vanilla setDeltaMovement plus its needsSync flag, so every client gets it; location: vanilla teleportTo, the same steps as /tp; health: vanilla setHealth
expert: Can fail | an unknown field (the error lists the ones it takes), nothing to change, a value of the wrong kind, health on an entity without health, a location outside the world or in a world that isn't loaded
expert: Reload | changes stay
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.set(a)
	-- FORMAT: set called without { }
	if type(a) ~= "table" then error("set needs { }: set{ entity = e, velocity = vec3(0, 1, 0) }", 2) end
	local e = a.entity
	local kind = kind_of[e]
	-- FORMAT: set{} without an entity or player
	if kind ~= "entity" and kind ~= "player" then
		error("set{}: entity must be an entity or a player, got " .. I.type_name(e), 2)
	end
	local t = {}
	for key, value in raw_next, a do t[key] = value end
	t.entity = slot_of[e]
	local l = a.location
	if l ~= nil then
		-- FORMAT: set{} location of the wrong kind
		if I.type_name(l) ~= "location" then error("set{}: location must be a location, got " .. I.type_name(l), 2) end
		local pos, rot = l[2], l[3]
		t.location = { slot_of[l[1]], pos[1], pos[2], pos[3], rot[1], rot[2] }
	end
	return J.entity_set(I.wire(t))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Materials and worlds (vanilla ids; "stone" means "minecraft:stone")
-- ---------------------------------------------------------------------------------------------------------------

-- make_slot (optional): Java function that gives an object its slot from its id (worlds; see Slots.java).
local function id_objects(kind, find, make_slot)
	local id_of = setmetatable({}, WEAK_KEYS)
	local by_id = {} -- strong: there are only a few hundred, and the same id must always give the same object
	local meta = {
		__index = function(_, key) I.no_field(kind, key, "no fields yet; tostring gives its id", 2) end,
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
--[[doc
id: world
name: world( )
kind: reading
group: Worlds
words: dimension level overworld nether end
syntax: world( "overworld" ) → world | nil
lead: A loaded world by its id: "overworld" means "minecraft:overworld". nil if there is none. Each world exists once, so == works.
arg: id | text · world | The world's id (a world is returned as is).
returns: world · nil
expert: Thread | main thread, like every script
expert: Cost | first time per world 2–10 µs, then 50–200 ns (guess, not measured)
expert: Under the hood | vanilla's dimension id; one object per world, kept
expert: Can fail | x not text or a world; an unknown or unloaded world gives nil
expert: Reload | world objects are made new after reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
--[[doc
id: block
name: block
kind: type
group: Blocks
words: place position block object
syntax: b.state   b.pos   b.world
lead: A place in a world: a world plus a block position. Each block exists once, so == works. Fields: b.state (the block_state there, nil if the chunk is not loaded), b.pos (block_pos), b.world (world). location:block() gives one.
expert: Java object | none kept: the world id and x, y, z live in Lua; b.state asks Java each time
expert: Stays valid | always (it is a place); b.state is nil while the chunk isn't loaded
expert: Compare | == works: one object per place
expert: Cost | b.state 1–5 µs (a Java call, never cached), b.pos and b.world 0.1–1 µs (guess, not measured)
status: unconfirmed
]]
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
I.STATE_META = STATE

function I.block_state_by_id(id)
	local s = states[id]
	if not s then
		s = setmetatable({ id }, STATE) -- [1]: the state number, so the wire can send a block_state inside a table
		state_id[s] = id
		states[id] = s
	end
	return s
end

-- block_state("oak_stairs[facing=north]"): vanilla's own text. block_state{ id = "oak_stairs", properties = { facing =
-- "north" } }: vanilla's codec shape (BlockState.CODEC; property values as text). block_state{ id = "oak_slab", type =
-- "top" }: the short form, every key but id is a property as a Lua value (checked like s:with). A table with a
-- properties key is always read as vanilla's codec. Missing properties take their default in every form.
--[[doc
name: block_state( )
kind: reading
group: Blocks
words: block properties stairs
syntax: block_state( "oak_stairs[facing=north]" | { id = text, ... } | { id = text, properties = { } } )
lead: A block state from vanilla's text, from id plus properties, or from vanilla's codec shape. Missing properties take their default.
arg: x | text · table · block_state | The state.
returns: block_state
expert: Thread | main thread, like every script
expert: Cost | from text 5–50 µs, the same state again reuses its object; a property: first time 3–20 µs, then 50–200 ns (guess, not measured)
expert: Under the hood | vanilla's state number (Block.getId) with vanilla's own parser and codec
expert: Can fail | unknown block or property, a value the property can't take (vanilla's message)
expert: Reload | states are made new after reload; the numbers can change on a Minecraft update
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.block_state(x)
	if state_id[x] then return x end
	if type(x) == "table" and debug_getmetatable(x) == nil then
		if x.properties ~= nil then return I.block_state_by_id(J.block_state_from_table(I.wire(x))) end
		-- FORMAT: block_state table without id
		if type(x.id) ~= "string" then error("block_state{}: needs id = \"oak_stairs\"", 0) end
		local props, any = {}, false
		for key, value in raw_next, x do
			if key ~= "id" then props[key] = value; any = true end
		end
		local s = lib.block_state(x.id)
		if any then s = state_methods.with(s, props) end
		return s
	end
	if type(x) ~= "string" then
		error("block_state needs text like \"oak_stairs[facing=north]\" or a table, got " .. I.type_name(x), 0)
	end
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
		I.no_field("block", key, "state, pos, world", 2)
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
-- Chunks (places: a world plus a chunk_pos, like blocks)
-- ---------------------------------------------------------------------------------------------------------------

local chunk_pos = setmetatable({}, WEAK_KEYS)  -- chunk -> { world id, x, z }
local chunk_objects = setmetatable({}, WEAK_VALUES) -- "world|x|z" -> chunk

-- "p UUID" (a player) or "e UUID SLOT" per line (ToLua.entities) -> a list of objects
local function entity_list(text)
	local list, n = {}, 0
	for kind, uuid, slot in gmatch(text, "(%a) (%S+) ?(%d*)\n") do
		n = n + 1
		list[n] = kind == "p" and I.player_by_uuid(uuid) or I.entity_from_java(uuid, tonumber(slot))
	end
	return list
end

local CHUNK_META = {
	__index = function(c, key)
		local p = chunk_pos[c]
		if key == "world" then return I.world_by_id(p[1]) end
		if key == "pos" then return I.new_chunk_pos(p[2], p[3]) end
		if key == "loaded" then return J.chunk_loaded(slot_of[I.world_by_id(p[1])], p[2], p[3]) end
		if key == "entities" then
			local text = J.chunk_entities(slot_of[I.world_by_id(p[1])], p[2], p[3])
			return text and entity_list(text) or nil
		end
		I.no_field("chunk", key, "world, pos, loaded, entities", 2)
	end,
	__newindex = read_only("chunk"),
	-- FORMAT: a chunk as text
	__tostring = function(c) local p = chunk_pos[c] return "chunk " .. p[2] .. " " .. p[3] .. " in " .. p[1] end,
	__metatable = false,
}
I.object_meta.chunk = CHUNK_META

-- world: the world's full id; x, z: whole numbers in int range (checked by the caller).
function I.chunk_at(world, x, z)
	local key = world .. "|" .. x .. "|" .. z
	local c = chunk_objects[key]
	if not c then
		c = setmetatable({}, CHUNK_META)
		chunk_pos[c] = { world, x, z }
		kind_of[c] = "chunk"
		chunk_objects[key] = c
	end
	return c
end

--[[doc
name: chunk( )
kind: reading
group: Worlds
words: column area entities loaded
syntax: chunk( world, chunk_pos ) → chunk
lead: A chunk: a 16 × 16 column of a world, full height. It is a place, so it always exists, loaded or not; each chunk exists once, so == works. location:chunk() gives the chunk a location is in. Reading never loads a chunk.
arg: world | world | The world.
arg: pos | chunk_pos | Which chunk: chunk_pos(x, z), in chunks (block x // 16).
returns: chunk
field: world | world | Its world.
field: pos | chunk_pos | Its position, in chunks.
field: loaded | boolean | Whether it is loaded right now.
field: entities | list of entity · nil | Every entity whose position is in this chunk, players too, as a new list each read; nil while the chunk isn't loaded.
example:
  local list = p.location:chunk().entities
  print(#list .. " entities in my chunk")
expert: Thread | main thread, like every script
expert: Cost | chunk( ): 0.5–2 µs, no Java; c.loaded 1–3 µs; c.entities 5 µs plus 1–3 µs per entity (guess, not measured)
expert: Under the hood | world + chunk_pos in Lua; loaded: vanilla getChunkNow (never loads); entities: vanilla's entity lookup over the chunk's box, then kept if their chunk position matches
expert: Can fail | world not a world, pos not a chunk_pos
expert: Reload | chunk objects are made new after reload
expert: Time limit | not checked by chunk( ) (plain Lua); checked by c.loaded and c.entities (they call into the game)
status: unconfirmed
]]
function lib.chunk(world, pos)
	-- FORMAT: chunk( ) with wrong arguments
	if kind_of[world] ~= "world" then error("chunk: world must be a world, got " .. I.type_name(world), 2) end
	if I.type_name(pos) ~= "chunk_pos" then error("chunk: pos must be a chunk_pos, got " .. I.type_name(pos), 2) end
	return I.chunk_at(tostring(world), pos[1], pos[2])
end

-- ---------------------------------------------------------------------------------------------------------------
-- Damage (a value: what hurt an entity; vanilla DamageSource)
-- ---------------------------------------------------------------------------------------------------------------

local DAMAGE = I.value_type("damage", { "type", "direct", "causing", "pos" }, {})
-- FORMAT: a damage as text
DAMAGE.__tostring = function(d) return "damage " .. d[1] end

--[[doc
id: damage
name: damage
kind: type
group: Entities
words: hurt attack cause source killer
syntax: d.type   d.direct   d.causing   d.pos
lead: What hurt an entity (vanilla's damage source), from events: e.damage_source in EntityDamageEvent, EntityDeathEvent, ... A value: your own copy, it can't be changed.
field: type | text | The damage type's id: "minecraft:player_attack", "minecraft:fall", "minecraft:arrow", ...
field: direct | entity · player · nil | What hit: the arrow, the zombie; nil if nothing did (falling, fire, ...).
field: causing | entity · player · nil | Who is behind it: the player who shot the arrow; nil if no one.
field: pos | vec3 · nil | Where it came from, when it has its own place (an explosion, a bed); nil otherwise.
expert: Java object | none kept: a plain Lua table with its fields
expert: Stays valid | always (a copy); direct and causing are entity objects and may be gone later
expert: Compare | == compares type, direct, causing and pos
expert: Cost | made when the event field is read: 2–10 µs (guess, not measured)
status: unconfirmed
]]
function I.new_damage(type_id, direct, causing, pos) return setmetatable({ type_id, direct, causing, pos }, DAMAGE) end

-- ---------------------------------------------------------------------------------------------------------------
-- Actions
-- ---------------------------------------------------------------------------------------------------------------

--[[doc
name: message{ }
kind: doing
group: Text
words: chat send tell tellraw
syntax: message{ player = player, text = text | component }
lead: Sends a chat message. Plain text is literal, like /tellraw.
arg: player | player | Who gets it.
arg: text | text · component | The message.
returns: boolean (false if the player is offline)
expert: Thread | main thread, like every script
expert: Cost | text 3–15 µs, a component 10–60 µs (guess, not measured)
expert: Under the hood | one Lua → Java call; packet System Chat (play, clientbound)
expert: Can fail | player or text of the wrong kind; an offline player gives false, no error
expert: Reload | sent messages stay in chat
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.message(a)
	if type(a) ~= "table" then error("message needs { }: message{ player = p, text = \"...\" }", 2) end
	if kind_of[a.player] ~= "player" then error("message: player must be a player, got " .. I.type_name(a.player), 2) end
	local text = a.text
	if type(text) == "table" and (I.type_name(text) == "component" or debug_getmetatable(text) == nil) then
		return J.message_component(slot_of[a.player], I.wire(text))
	end
	if type(text) ~= "string" then error("message: text must be text or a component, got " .. I.type_name(text), 2) end
	return J.message(slot_of[a.player], text)
end

-- ---------------------------------------------------------------------------------------------------------------
-- Packet actions: straight on vanilla's packets, sent by nms/Packets.java (the one send core).
-- Every field crosses in ONE wire call; Java checks the fields and names the wrong one. Nothing is returned.
-- What each action takes is in its doc block below (the doc block is the one description; don't copy it here).
-- ---------------------------------------------------------------------------------------------------------------

-- The receivers as slot numbers: player = p or players = { p1, p2, ... } (exactly one of them).
local function receivers(a, what)
	local p, list = a.player, a.players
	-- FORMAT: player/players wrong
	if (p == nil) == (list == nil) then error(what .. ": give player = p or players = { ... } (one of them)", 3) end
	if p ~= nil then
		if kind_of[p] ~= "player" then error(what .. ": player must be a player, got " .. I.type_name(p), 3) end
		return { slot_of[p] }
	end
	if type(list) ~= "table" or debug_getmetatable(list) ~= nil then
		error(what .. ": players must be a list of players, got " .. I.type_name(list), 3)
	end
	local n = 0
	for key in raw_next, list do
		n = n + 1
		-- FORMAT: players is not a plain list
		if type(key) ~= "number" or key < 1 or key ~= math.floor(key) then
			error(what .. ": players must be a list (no other keys), found key " .. tostring(key), 3)
		end
	end
	local slots = {}
	for i = 1, n do
		local q = list[i]
		-- FORMAT: a list entry that is not a player (nil: a hole in the list)
		if kind_of[q] ~= "player" then error(what .. ": players[" .. i .. "] must be a player, got " .. I.type_name(q), 3) end
		slots[i] = slot_of[q]
	end
	return slots
end

local function action_table(a, what)
	-- FORMAT: action called without { }
	if type(a) ~= "table" then error(what .. " needs { }: " .. what .. "{ player = p, ... }", 3) end
end

--[[doc
name: particle{ }
kind: doing
group: Effects
words: effect dust flame
syntax: particle{ player = player | players = { player, ... }, particle = text | table, pos = vec3 | { vec3, ... }, dist = vec3, max_speed = vec3, count = number, override_limiter = boolean, always_show = boolean, randomization_type = text }
lead: Shows particles at one or many positions.
arg: player / players | player · list of player | Who sees them. Exactly one of the two.
arg: particle | text · table | "flame", "dust{color:[1,0,0],scale:1}" or { type = "dust", ... }.
arg: pos | vec3 · list of vec3 | One or many positions.
arg: dist? | vec3 | How far particles spread around the position on each axis.
arg: max_speed? | vec3 | Their speed.
arg: count? | number | How many per position. Default 0: one particle, and max_speed is its exact motion instead of a random spread.
arg: override_limiter? | boolean | Shows it even if the player set particles to minimal. Default false.
arg: always_show? | boolean | Shows it from far away (vanilla's long distance). Default false.
arg: randomization_type? | text | default, alternative, alternative_with_speed.
returns: nothing
note: Positions × count (count 0 counts as 1) over the config's particle_limit is refused with an error.
expert: Thread | main thread, like every script
expert: Cost | 5–50 µs per call plus 0.2–2 µs per position and player (guess, not measured)
expert: Under the hood | one Lua → Java call; packet Level Particles (play, clientbound), one per position, bundled by 4096, built once and shared by every receiver
expert: Can fail | positions × count over particle_limit; any field vanilla's codec refuses (named in the error)
expert: Reload | particles already shown play out
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.particle(a)
	action_table(a, "particle")
	-- The config's particle_limit is checked ONLY here, before the list is packed for Java (a huge list would cost
	-- memory first). Positions: a plain list counts its entries, anything else counts as one (Java checks the kinds).
	local limit = I.config.particle_limit
	if limit then
		local pos, count = a.pos, a.count
		local n = type(pos) == "table" and debug_getmetatable(pos) == nil and #pos or 1
		local c = (type(count) == "number" or type(count) == "cdata") and tonumber(count) or 0 -- cdata: a 64-bit number
		local total = n * (c >= 1 and c or 1)
		if total > limit then
			-- FORMAT: particle limit from the config
			error("particle{}: " .. I.number_text(total) .. " particles (positions x count) is over the config's "
				.. "particle_limit of " .. I.number_text(limit), 2)
		end
	end
	J.particle(I.wire({
		players = receivers(a, "particle"), particle = a.particle, pos = a.pos, dist = a.dist,
		max_speed = a.max_speed, count = a.count, override_limiter = a.override_limiter, always_show = a.always_show,
		randomization_type = a.randomization_type,
	}))
end

--[[doc
name: sound{ }
kind: doing
group: Effects
words: play audio noise
syntax: sound{ player = player | players = { player, ... }, sound = text | { sound_id = text, range = number }, source = text, pos = vec3, entity = entity | player, volume = number, pitch = number, seed = number }
lead: Plays a sound to one player or a list of players.
arg: player / players | player · list of player | Who hears it. Exactly one of the two.
arg: sound | text · table | A sound id like "entity.player.levelup", or { sound_id, range }.
arg: source | text | Needed. Which volume slider plays it: one of vanilla's sound sources, as in /playsound ("master", "music", "ambient", ...). A wrong name gets an error that lists them all.
arg: pos / entity | vec3 · entity · player | Needed, one of the two: where it plays; entity follows that entity or player (nothing plays if it is gone).
arg: volume? | number | Default 1.
arg: pitch? | number | Default 1.
arg: seed? | number | Picks which file of the sound plays. Random by default, as in vanilla. For one exact file use sound_seed( ).
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | 3–20 µs plus 0.5–2 µs per player (guess, not measured)
expert: Under the hood | one Lua → Java call; packet Sound or Sound Entity (play, clientbound)
expert: Can fail | no source or no pos/entity, an unknown source (the error lists them), fields vanilla refuses
expert: Reload | sounds already playing keep playing
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.sound(a)
	action_table(a, "sound")
	local entity = a.entity
	if entity ~= nil then
		-- FORMAT: sound entity is not an entity
		if kind_of[entity] ~= "player" and kind_of[entity] ~= "entity" then
			error("sound: entity must be an entity or a player, got " .. I.type_name(entity), 2)
		end
		entity = slot_of[entity]
	end
	J.sound(I.wire({
		players = receivers(a, "sound"), sound = a.sound, source = a.source, pos = a.pos, entity = entity,
		volume = a.volume, pitch = a.pitch, seed = a.seed,
	}))
end

--[[doc
name: sound_seed( )
kind: reading
group: Effects
words: variant exact precise file pick ogg
syntax: sound_seed( sound, file )
lead: The seed that makes sound{ } play one exact file of a sound, instead of a random one.
arg: sound | text | A vanilla sound id like "ambient.basalt_deltas.additions".
arg: file | text | One of its files, as in vanilla's sounds.json, like "ambient/nether/basalt_deltas/basaltground1". A wrong name gets an error that lists the sound's files.
returns: number
note: The player's game picks the file from its own sounds.json. A resource pack that changes the sound's file list can make the player hear another file; the server can't see that. Lullaby reads Minecraft's own sounds.json for the version it is built for and works the seed out the way the game does, so a Minecraft update can't make a seed silently wrong. The seed is found once per sound and then kept.
example:
  local seed = sound_seed("ambient.basalt_deltas.additions", "ambient/nether/basalt_deltas/basaltground1")
  sound{ player = p, sound = "ambient.basalt_deltas.additions", source = "ambient", entity = p, seed = seed }
expert: Thread | main thread, like every script
expert: Cost | first time per sound 10–500 µs (it searches the seed), then 1–5 µs (guess, not measured)
expert: Under the hood | one Lua → Java call; SoundSeeds.java runs the game's own file pick over vanilla's sounds.json
expert: Can fail | unknown sound, or a file the sound doesn't have (the error lists its files)
expert: Reload | found seeds are kept while the server runs
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.sound_seed(sound, file)
	-- FORMAT: wrong arguments for sound_seed()
	if type(sound) ~= "string" then error("sound_seed: sound must be a sound id, got " .. I.type_name(sound), 2) end
	if type(file) ~= "string" then error("sound_seed: file must be a file name, got " .. I.type_name(file), 2) end
	return J.sound_seed(sound, file)
end

--[[doc
name: stop_sound{ }
kind: doing
group: Effects
words: silence mute
syntax: stop_sound{ player = player | players = { player, ... }, sound = text, source = text }
lead: Stops sounds. Without sound and source it stops every sound.
arg: player / players | player · list of player | Who.
arg: sound? | text | Only this sound.
arg: source? | text | Only this source.
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | 3–15 µs plus 0.5–2 µs per player (guess, not measured)
expert: Under the hood | one Lua → Java call; packet Stop Sound (play, clientbound)
expert: Can fail | receivers of the wrong kind, an unknown source
expert: Reload | nothing to undo
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.stop_sound(a)
	action_table(a, "stop_sound")
	J.stop_sound(I.wire({ players = receivers(a, "stop_sound"), sound = a.sound, source = a.source }))
end

-- Java hands a value over as a kind word plus values (ToLua.java; "v" is a plain value, "c" a component, EventBridge
-- adds "v" for enums, keys and UUIDs); this is the one place that turns them into Lua values. Used by event fields,
-- entity fields and java.wrap( ). Nothing (no kind) gives nil.
function I.from_java(kind, a, b, c, d, f, g, h, i)
	if kind == "v" then return a end
	if kind == "player" then return I.player_by_uuid(a) end
	if kind == "entity" then return I.entity_from_java(a, b) end
	if kind == "vec3" then return I.new_vec3(a, b, c) end
	if kind == "location" then return I.new_location(I.world_by_id(a), I.new_vec3(b, c, d), I.new_yaw_pitch(f, g)) end
	if kind == "world" then return I.world_by_id(a) end
	if kind == "block" then return I.block_at(a, b, c, d) end
	if kind == "chunk" then return I.chunk_at(a, b, c) end
	if kind == "block_state" then return I.block_state_by_id(a) end
	if kind == "item_stack" then return I.new_item(a) end
	if kind == "c" then return I.new_component(a, b) end
	if kind == "block_pos" then return I.new_block_pos(a, b, c) end
	if kind == "aabb" then return I.new_aabb(I.new_vec3(a, b, c), I.new_vec3(d, f, g)) end
	if kind == "damage" then
		return I.new_damage(a, nested_entity(b, c), nested_entity(d, f), g and I.new_vec3(g, h, i) or nil)
	end
	return nil
end
