-- Lullaby library: game objects, block states, and the first action.
--
-- Objects (player, world, block) are hand-made: each one exists once (cached), so == works and they can be table
-- keys. Their identity (a UUID, an id, a position) is kept in private tables, never on the object, and every field
-- is read from Java when asked. Fields and methods are added only with Kupfermeister's yes.
--   player("Notch") / player(uuid text)  -> player or nil (online, or has played here)     p.name  p.uuid  p.online
--   world("overworld")                   -> world ("minecraft:overworld") or nil
--   block (a PLACE: world + position)    b.state (block_state, nil if the chunk is not loaded)  b.pos  b.world
--   block_state("oak_stairs[facing=north]") / block_state{ id = "oak_stairs", facing = "north" } /
--   block_state{ id = "oak_stairs", properties = { facing = "north" } } (vanilla's codec shape) -> block_state (exists once per state, so == is free)
--                                        s.type (id)  s.<property> (s.facing)  s:with{ facing = "south" }
--   message{ player = p, text = "Hi" | component("<red>Hi") | { text = "Hi", color = "red" } }   chat message (a string
--       is literal text, as /tellraw "Hi"; a table is a component) -> true, false if p is offline
local J, lib, I = ...

local type, error, setmetatable, tostring, raw_next = type, error, setmetatable, tostring, next
local debug_getmetatable = I.debug_getmetatable

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of
I.object_meta = {} -- kind -> metatable of player, world, block objects (the wire tags them, see lazy.lua)
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

--[[doc
name: player( )
kind: reading
group: Players
words: get find user name uuid
syntax: player( text ) → player | nil
lead: A player by name or UUID: online, or one who has played on this server before. nil if none. Each player exists once, so == works.
arg: x | text · player | Name or UUID (a player is returned as is).
returns: player · nil
status: unconfirmed
]]
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
expert: Packet | System Chat (play, clientbound)
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
expert: Packet | Level Particles (play, clientbound), one per position, bundled by 4096
expert: Lua → Java | one call
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
syntax: sound{ player = player | players = { player, ... }, sound = text | { sound_id = text, range = number }, source = text, pos = vec3, entity = player, volume = number, pitch = number, seed = number }
lead: Plays a sound to one player or a list of players.
arg: player / players | player · list of player | Who hears it. Exactly one of the two.
arg: sound | text · table | A sound id like "entity.player.levelup", or { sound_id, range }.
arg: source | text | Needed. Which volume slider plays it: one of vanilla's sound sources, as in /playsound ("master", "music", "ambient", ...). A wrong name gets an error that lists them all.
arg: pos / entity | vec3 · player | Needed, one of the two: where it plays; entity follows that player.
arg: volume? | number | Default 1.
arg: pitch? | number | Default 1.
arg: seed? | number | Picks which file of the sound plays. Random by default, as in vanilla. For one exact file use sound_seed( ).
returns: nothing
expert: Packet | Sound / Sound Entity (play, clientbound)
expert: Lua → Java | one call
status: unconfirmed
]]
function lib.sound(a)
	action_table(a, "sound")
	local entity = a.entity
	if entity ~= nil then
		-- FORMAT: sound entity is not a player (other entities come with the entity object)
		if kind_of[entity] ~= "player" then error("sound: entity must be a player, got " .. I.type_name(entity), 2) end
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
expert: Lua → Java | one call
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
expert: Packet | Stop Sound (play, clientbound)
status: unconfirmed
]]
function lib.stop_sound(a)
	action_table(a, "stop_sound")
	J.stop_sound(I.wire({ players = receivers(a, "stop_sound"), sound = a.sound, source = a.source }))
end
