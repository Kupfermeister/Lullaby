-- Lullaby library: worlds, blocks, block states, block snapshots and chunks (the places; world folders: worlds.lua).
--
-- Objects are made as entities.lua describes: each exists once, identity in private tables, fields read from Java.
--   world("overworld")                   -> world ("minecraft:overworld") or nil
--   chunk(world, chunk_pos) / location:chunk()  -> chunk (a PLACE: world + chunk_pos)  c.world c.pos c.loaded c.entities
--   block (a PLACE: world + position)    b.state (block_state, nil if the chunk is not loaded)  b.pos  b.world  b.location (its center);  location.block gives one (nil: chunk not loaded)
--   block_state("oak_stairs[facing=north]") / block_state{ id = "oak_stairs", facing = "north" } /
--   block_state{ id = "oak_stairs", properties = { facing = "north" } } (vanilla's codec shape) -> block_state (exists once per state, so == is free)
--                                        s.type (id)  s.<property> (s.facing)  s:with{ facing = "south" }
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber, raw_next = type, error, setmetatable, tostring, tonumber, next
local gmatch = string.gmatch
local debug_getmetatable = I.debug_getmetatable

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

local function read_only(kind)
	return function(_, key) error(kind .. " fields can't be changed (." .. tostring(key) .. ")", 2) end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Materials and worlds (vanilla ids; "stone" means "minecraft:stone")
-- ---------------------------------------------------------------------------------------------------------------

-- make_slot (optional): Java function that gives an object its slot from its id (worlds; see Slots.java).
local function id_objects(kind, find, make_slot)
	local id_of = setmetatable({}, WEAK_KEYS)
	local by_id = {} -- strong: there are only a few hundred, and the same id must always give the same object
	local meta = {
		__index = function(_, key) I.no_field(kind, key, "no fields yet", 2) end,
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
words: dimension level overworld nether end save saved data store persist
syntax: world( "overworld" ) → world | nil
lead: A loaded world by its id: "overworld" means "minecraft:overworld". nil if there is none (worlds( ) gives every world, also unloaded ones). Each world exists once, so == works.
arg: id | text · world | The world's id (a world is returned as is).
returns: world · nil
field: id | text | The full id: "minecraft:arena_7".
field: string | text | The text form: the full id, the same as id. world(w.string) gives it again (while loaded).
field: name | text | The name create_world{ } and load_world{ } take: "arena_7" (the id without "minecraft:"; another namespace stays: "myserver:arena_7").
field: loaded | boolean | Whether the world is loaded right now. Worlds from worlds( ) can be unloaded; id, name and loaded work then too.
field: data | table | Saved data of this world, a table that stays like server.data (it takes the same values, see server). It is saved when this world saves (autosave, /save-all, server stop) and before /lullaby reload, into the world's own folder, so it travels with the world: copy_world{ } copies it, delete_world{ } deletes it. Only while the world is loaded. world.data = { } starts over.
set: data | world.data = { } (a new table; only while loaded)
note: world.data lives in the world's folder as data/lullaby.snbt (world/dimensions/minecraft/<name>/data/lullaby.snbt), readable SNBT you may edit: as with server.data, a change by hand wins and loads at the next /lullaby reload. When the world unloads (unload_world{ }, delete_world{ }, another plugin), its table is emptied and locked: using it is a red error, load the world and read world.data again. unload_world{ save = false } doesn't save it, like the world itself. /lullaby data world <world> [path] shows it.
example:
  local arena = world("arena")
  arena.data.rounds = (arena.data.rounds or 0) + 1
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
syntax: block.state   block.pos   block.world   block.location
lead: A place in a world: a world plus a block position. Each block exists once, so == works. location.block gives one.
field: state | block_state · nil | The block state there; nil if the chunk is not loaded.
field: pos | block_pos | Its position.
field: world | world | Its world.
field: location | location | A location at the block's center, yaw 0, pitch 0.
method: a == b | boolean | The same place.
expert: Java object | none kept: the world id and x, y, z live in Lua; block.state asks Java each time
expert: Stays valid | always (it is a place); block.state is nil while the chunk isn't loaded
expert: Compare | == works: one object per place
expert: Cost | block.state 1–5 µs (a Java call, never cached), block.pos and block.world 0.1–1 µs (guess, not measured)
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
		if key == "string" then return tostring(s) end -- vanilla's text: block_state( ) reads it back
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
arg: state | text · table · block_state | The state.
returns: block_state
field: type | text | The block's id, like "minecraft:oak_stairs".
field: <property> | boolean · number · text | Each property by its name: s.facing == "north", s.waterlogged == false, s.age == 3. Can't be changed: use s:with{ }.
method: :with{ property = value, ... } | block_state | The state with these properties changed (vanilla's setValue): s:with{ facing = "south" }. s itself stays as it was.
method: a == b | boolean | The same state (each state exists once).
field: string | text | Vanilla's text, like "minecraft:oak_stairs[facing=north,half=bottom,...]": block_state(s.string) makes it again, and /setblock takes it.
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
		-- the center: tools/docs/design/types.lua takes a block as a location at its center
		if key == "location" then
			return I.new_location(I.world_by_id(pos[1]), I.new_vec3(pos[2] + 0.5, pos[3] + 0.5, pos[4] + 0.5))
		end
		I.no_field("block", key, "state, pos, world, location", 2)
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
		if key == "data" then return I.chunk_data(c, 2) end -- data.lua
		I.no_field("chunk", key, "world, pos, loaded, entities, data", 2)
	end,
	__newindex = function(c, key, value)
		if key == "data" then return I.set_chunk_data(c, value, 2) end -- data.lua
		-- FORMAT: a chunk field that can't be changed
		error("chunk fields can't be changed (." .. tostring(key) .. "), only c.data", 2)
	end,
	-- FORMAT: a chunk as text
	__tostring = function(c) local p = chunk_pos[c] return "chunk " .. p[2] .. " " .. p[3] .. " in " .. p[1] end,
	__metatable = false,
}
I.object_meta.chunk = CHUNK_META

-- A chunk's world id, x and z (data.lua keeps c.data by them).
function I.chunk_parts(c)
	local p = chunk_pos[c]
	return p[1], p[2], p[3]
end

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
words: column area entities loaded save saved data store persist claim
syntax: chunk( world, chunk_pos ) → chunk
lead: A chunk: a 16 × 16 column of a world, full height. It is a place, so it always exists, loaded or not; each chunk exists once, so == works. location:chunk() gives the chunk a location is in. Reading never loads a chunk.
arg: world | world | The world.
arg: pos | chunk_pos | Which chunk: chunk_pos(x, z), in chunks (block x // 16).
returns: chunk
field: world | world | Its world.
field: pos | chunk_pos | Its position, in chunks.
field: loaded | boolean | Whether it is loaded right now.
field: entities | list of entity · nil | Every entity whose position is in this chunk, players too, as a new list each read; nil while the chunk isn't loaded.
field: data | table | Saved data of this chunk, a table that stays like server.data (it takes the same values, see server). It is saved inside the chunk itself, so it travels with the chunk: a chunk that is reset or regenerated loses it. Reading it needs the chunk loaded (else a red error: check chunk.loaded). Keep data of single blocks here too, under a key of your own, like chunk.data[x .. "," .. y .. "," .. z]. One text (a value or a key) can be at most 65535 bytes here, the most a chunk holds; a longer one is left out with a red error. chunk.data = { } starts over.
set: data | chunk.data = { } (a new table; only while loaded)
note: A table you keep (local data = chunk.data) stays the chunk's real data, also after the chunk unloads: changes are still saved (at the next autosave Lullaby loads the chunk in the background to write them) and chunk.data gives the same table when the chunk is back. So every chunk's data read since the last /lullaby reload stays in memory and is checked for changes when its world saves. Only if something else changed the chunk's saved data meanwhile (a world editing tool) the chunk's version wins: the old table is then emptied and locked, and using it is a red error. The same happens when the chunk's world unloads. /lullaby data chunk [path] shows the data of the chunk you stand in.
example:
  -- a simple claim
  local chunk = player.location:chunk()
  if chunk.data.owner == nil then chunk.data.owner = player end
expert: Thread | main thread, like every script
expert: Cost | chunk( ): 0.5–2 µs, no Java; chunk.loaded 1–3 µs; chunk.entities 5 µs plus 1–3 µs per entity; chunk.data: the first read in a chunk 10–100 µs (it reads the chunk's saved data), then as any Lua table (guess, not measured)
expert: Under the hood | world + chunk_pos in Lua; loaded: vanilla getChunkNow (never loads); entities: vanilla's entity lookup over the chunk's box, then kept if their chunk position matches; data: one entry "lullaby:data" in the chunk's plugin data (ChunkBukkitValues in the region file), written when the chunk unloads and when its world saves, only when it changed (nms/ChunkData.java)
expert: Can fail | world not a world, pos not a chunk_pos; chunk.data while the chunk isn't loaded
expert: Reload | chunk objects are made new after reload; chunk.data is written into the chunks before and read again after
expert: Time limit | not checked by chunk( ) (plain Lua); checked by chunk.loaded, chunk.entities and the first chunk.data in a chunk (they call into the game)
status: unconfirmed
]]
function lib.chunk(world, pos)
	-- FORMAT: chunk( ) with wrong arguments
	if kind_of[world] ~= "world" then error("chunk: world must be a world, got " .. I.type_name(world), 2) end
	if I.type_name(pos) ~= "chunk_pos" then error("chunk: pos must be a chunk_pos, got " .. I.type_name(pos), 2) end
	return I.chunk_at(tostring(world), pos[1], pos[2])
end

-- A block snapshot: a value whose state can be changed (only inside an event, where the change goes back: events.lua).
local SNAPSHOT = I.value_type("block_snapshot", { "block", "state" }, {})
-- FORMAT: a block_snapshot as text
SNAPSHOT.__tostring = function(s) return "block_snapshot " .. tostring(rawget(s, 2)) .. " at " .. tostring(rawget(s, 1)) end
local STATE_META = I.STATE_META

--[[doc
id: block_snapshot
name: block_snapshot
kind: type
group: Blocks
words: snapshot blockstate new state grow form spread tree structure
syntax: snapshot.block   snapshot.state   snapshot.state = block_state
lead: A block and the state it has, would get or had, from events: event.new_state in BlockFormEvent, BlockGrowEvent, ..., event.blocks in StructureGrowEvent (a list of them). Changing snapshot.state inside on{ } changes what the event places, where the event's page says the field can be changed; anywhere else a change is a red error when the handler ends (never silently lost).
field: block | block | Where.
field: state | block_state | The state. The only field that can be changed.
set: state | snapshot.state = block_state
example:
  -- trees grow birch logs instead of oak logs
  on{ "StructureGrowEvent", function(event)
    for _, snapshot in ipairs(event.blocks) do
      if snapshot.state.type == "minecraft:oak_log" then snapshot.state = block_state("birch_log") end
    end
  end }
expert: Java object | none kept: a Lua value (block and block_state); in an event, the change is written back into Paper's BlockState when the handler ends or first waits
expert: Stays valid | always (a copy)
expert: Compare | == compares block and state
expert: Cost | made when the event field is read: 2–10 µs per snapshot (guess, not measured)
status: unconfirmed
]]
function I.new_block_snapshot(block, state)
	return setmetatable({ block, state }, SNAPSHOT)
end
-- s.state = x: the fields live in [1], [2], so the name reaches __newindex; checked here (Java can't see an object's
-- kind), then stored in [2], where events.lua looks for a change.
SNAPSHOT.__newindex = function(s, key, value)
	-- FORMAT: a block_snapshot field that can't be changed, or state of the wrong kind
	if key ~= "state" then error("block_snapshot: only state can be changed (." .. tostring(key) .. ")", 2) end
	if I.debug_getmetatable(value) ~= STATE_META then
		error("block_snapshot: state must be a block_state, got " .. I.type_name(value), 2)
	end
	rawset(s, 2, value)
end

