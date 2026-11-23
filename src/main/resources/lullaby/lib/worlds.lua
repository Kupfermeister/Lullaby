-- Lullaby library: worlds as folders on disk (Java side: nms/Worlds.java).
--
--   worlds()                                        -> { { id = "minecraft:arena_7", loaded = true }, ... }
--   create_world{ id, generator, biome, layers, seed, structures }   makes and loads a new world -> world
--   load_world{ id }                                -> world (already loaded: the same world)
--   unload_world{ world, move_to, save }            players in it go to move_to (default: the main world's spawn)
--   copy_world{ from, to }                          waits until the copy is done (a worker thread copies)
--   delete_world{ id, move_to }                     unloads it first if loaded; kept in temp until the next start
-- Names are ids like world( ) takes: "arena_7" means "minecraft:arena_7"; a world object works too. Every value is
-- checked in Java; Lua only turns world objects into their id and location objects into numbers.
local J, lib, I = ...

local type, error, tostring = type, error, tostring
local gmatch = string.gmatch
local kind_of, slot_of = I.kind_of, I.slot_of

-- A world object or a name -> the name as text; anything else is an error at the script's line (level 3).
local function name_of(value, what, field)
	if value == nil or type(value) == "string" then return value end
	if kind_of[value] == "world" then return tostring(value) end
	-- FORMAT: a world field that is neither a name nor a world
	error(what .. ": " .. field .. " must be a world name or a world, got " .. I.type_name(value), 3)
end

-- move_to -> world slot, x, y, z, yaw, pitch (slot 0: none given).
local function place(l, what)
	if l == nil then return 0, 0, 0, 0, 0, 0 end
	-- FORMAT: move_to of the wrong kind
	if I.type_name(l) ~= "location" then error(what .. ": move_to must be a location, got " .. I.type_name(l), 3) end
	local pos, rot = l[2], l[3]
	return slot_of[l[1]], pos[1], pos[2], pos[3], rot[1], rot[2]
end

local function needs_table(a, what, example)
	-- FORMAT: a world function called without { }
	if type(a) ~= "table" then error(what .. " needs { }: " .. example, 3) end
end

--[[doc
name: worlds( )
kind: reading
group: Worlds
words: list folders maps arenas levels dimensions saved
syntax: worlds( ) → { { id = text, loaded = boolean }, ... }
lead: Every world the server has: the loaded ones and every world folder on disk, sorted by id. Each entry is a table with id (the full id, "minecraft:arena_7") and loaded. world( id ) gives the world of a loaded one.
returns: list of table
example:
  for _, world in ipairs(worlds()) do
    if not world.loaded then load_world{ id = world.id } end
  end
expert: Thread | main thread, like every script
expert: Cost | reads the world folders on disk: 0.1–2 ms (guess, not measured); don't call it every tick
expert: Under the hood | the folders in world/dimensions/<namespace>/ that have a data or region folder, plus every loaded world (nms/Worlds.java)
expert: Can fail | the world folders can't be read
expert: Reload | nothing to undo
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.worlds()
	local out = {}
	for id, loaded in gmatch(J.world_list(), "([^\t\n]+)\t([01])\n") do
		out[#out + 1] = { id = id, loaded = loaded == "1" }
	end
	return out
end

--[[doc
name: create_world{ }
kind: doing
group: Worlds
words: new world make generate void empty flat superflat arena map biome seed dimension
syntax: create_world{ id = text, generator = text, biome = text, layers = { { block = text, height = number }, ... }, seed = number | text, structures = boolean } → world
lead: Makes a new world and loads it. Its generator is saved in the world, so it stays the same after load_world{ } and in every copy.
arg: id | text | The new world's name: a-z, 0-9, _, - and . ("arena_7", or "myserver:arena_7" for another namespace). Its folder is world/dimensions/minecraft/arena_7.
arg: generator? | text | "void" (nothing at all), "flat", "normal" (the default), "large_biomes" or "amplified".
arg: biome? | text | Only for "void" and "flat": the biome everywhere ("cherry_grove"); it sets sky, fog, water and grass color, weather, particles and music. Default "the_void" for void, "plains" for flat. A wrong name is an error that suggests close ones.
arg: layers? | list of table | Only for "flat": the layers from the bottom up, each { block = "stone", height = 3 }. Default: bedrock, two dirt, grass block.
arg: seed? | number · text | The seed; text works as in the create world screen. Default random.
arg: structures? | boolean | Villages, trial chambers and so on. Default false for void and flat, true for the others.
returns: world
note: The server stands still while the world is made: a void or flat world is quick, normal terrain takes longer. For arenas, build one map and copy it with copy_world{ }. A void world's spawn is 0, 64, 0 in the air. The world is not loaded again by itself after a restart: call load_world{ } when your script starts.
example:
  local arena = create_world{ id = "arena", generator = "void", biome = "cherry_grove" }
  local snow = create_world{ id = "snow", generator = "flat", biome = "snowy_plains",
    layers = { { block = "bedrock", height = 1 }, { block = "snow_block", height = 3 } } }
expert: Thread | main thread, like every script
expert: Cost | the server stands still while Paper makes the world: void or flat 0.1–1 s, normal terrain several seconds (guess, not measured)
expert: Under the hood | Paper's WorldCreator; void and flat as vanilla's flat world settings (JSON, the same as server.properties' generator-settings), with decorations off
expert: Can fail | an unknown field (the error lists the ones it takes), a bad name, a name that is taken, an unknown generator, biome or block, biome or layers with a generator that doesn't take them, layers higher than the world, the name is being copied right now
expert: Reload | the world stays loaded
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.create_world(a)
	needs_table(a, "create_world{}", 'create_world{ id = "arena", generator = "void" }')
	return I.world_by_id(J.world_create(I.wire(a)))
end

local LOAD_TAKES = I.takes(0, "id")
--[[doc
name: load_world{ }
kind: doing
group: Worlds
words: open start world folder map arena
syntax: load_world{ id = text | world } → world
lead: Loads a world from its folder and gives it. A world that is loaded already is given as it is.
arg: id | text · world | The world's name ("arena_7"); worlds( ) lists them.
returns: world
note: The server stands still while the world loads (short for a finished map). Worlds made with another environment than the overworld's (a nether-like world from another plugin) load as overworld-like.
expert: Thread | main thread, like every script
expert: Cost | the server stands still while it loads: 0.05–0.5 s (guess, not measured)
expert: Under the hood | Paper's WorldCreator without settings: the generator comes from the world itself
expert: Can fail | an unknown field (the error lists the ones it takes), a bad name, no such world, the world is being copied right now
expert: Reload | the world stays loaded
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.load_world(a)
	needs_table(a, "load_world{}", 'load_world{ id = "arena" }')
	I.only(a, "load_world{}", LOAD_TAKES, 2)
	return I.world_by_id(J.world_load(name_of(a.id, "load_world{}", "id")))
end

local UNLOAD_TAKES = I.takes(0, "world", "move_to", "save")
--[[doc
name: unload_world{ }
kind: doing
group: Worlds
words: close stop world arena map
syntax: unload_world{ world = world | text, move_to = location, save = boolean } → boolean
lead: Moves every player in the world to move_to, then unloads it. Gives false if it wasn't loaded.
arg: world | world · text | The world, or its name.
arg: move_to? | location | Where its players go. Default: the main world's spawn.
arg: save? | boolean | Default true. false throws away every change since the last save.
returns: boolean
note: The main world can't be unloaded. A world_unload handler that cancels the event keeps the world loaded: then this is an error.
expert: Thread | main thread, like every script
expert: Cost | the server stands still while it saves: 0.05–1 s (guess, not measured)
expert: Under the hood | players are teleported, then Paper's unloadWorld
expert: Can fail | an unknown field (the error lists the ones it takes), a bad name, the main world, move_to in that same world or in a world that isn't loaded, a cancelled world_unload
expert: Reload | nothing to undo
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.unload_world(a)
	needs_table(a, "unload_world{}", "unload_world{ world = arena }")
	I.only(a, "unload_world{}", UNLOAD_TAKES, 2)
	local save = a.save
	-- FORMAT: unload_world{} save of the wrong kind
	if save ~= nil and type(save) ~= "boolean" then
		error("unload_world{}: save must be true or false, got " .. I.type_name(save), 2)
	end
	return J.world_unload(name_of(a.world, "unload_world{}", "world"), save ~= false,
		place(a.move_to, "unload_world{}"))
end

local COPY_TAKES = I.takes(0, "from", "to")
--[[doc
name: copy_world{ }
kind: doing
group: Worlds
words: duplicate clone instance arena map template reset
syntax: copy_world{ from = text | world, to = text }
lead: Copies a world into a new one and waits until the copy is done. Everything comes along: blocks, entities, the generator and the world's settings. Load the copy with load_world{ }.
arg: from | text · world | The world to copy. It must be unloaded (a loaded world may be half saved).
arg: to | text | The new world's name. It must not exist.
returns: nothing
note: Only inside a task (an event handler, a command function or run{ }), because it waits. The server doesn't stand still: another thread copies. Until it is done, both names can't be loaded, copied or deleted. If the server stops or crashes during a copy, the new world isn't there (no half copies).
example:
  copy_world{ from = "map_castle", to = "arena_7" }
  local arena = load_world{ id = "arena_7" }
expert: Thread | the files are copied on a worker thread ("Lullaby world files"); the task waits on the main thread
expert: Cost | no lag; the wait is about 0.1–1 s per 100 MB (guess, not measured)
expert: Under the hood | the folder is copied into plugins/lullaby/temp and moved into place when complete; Paper's data/paper/metadata.dat (the world's UUID) is left out, or Paper would refuse the copy as a duplicate
expert: Can fail | an unknown field (the error lists the ones it takes), outside a task, a bad name, from is loaded or doesn't exist, to exists, from and to are the same, either is being copied right now, the disk (the error says what failed)
expert: Reload | the waiting task ends; the copy still finishes
expert: Time limit | checked: it calls into the game; waiting doesn't count
status: unconfirmed
]]
function lib.copy_world(a)
	needs_table(a, "copy_world{}", 'copy_world{ from = "map", to = "arena_1" }')
	I.only(a, "copy_world{}", COPY_TAKES, 2)
	-- FORMAT: copy_world{} outside a task
	if not I.can_wait() then
		error("copy_world{} waits until the copy is done, so it only works inside a task (an event handler, a command or run{})", 2)
	end
	local job = J.world_copy(name_of(a.from, "copy_world{}", "from"), name_of(a.to, "copy_world{}", "to"))
	local result = J.world_job(job)
	while result == nil do
		lib.wait("1t")
		result = J.world_job(job)
	end
	if result ~= "" then error(result, 2) end
end

local DELETE_TAKES = I.takes(0, "id", "move_to")
--[[doc
name: delete_world{ }
kind: doing
group: Worlds
words: remove erase destroy world folder arena map
syntax: delete_world{ id = text | world, move_to = location }
lead: Deletes a world: it is gone from the server at once, and its name is free again. A loaded one is unloaded first (not saved) and its players go to move_to.
arg: id | text · world | The world, or its name.
arg: move_to? | location | Where its players go. Default: the main world's spawn.
returns: nothing
note: Last chance: until the next server start, the world waits in plugins/lullaby/temp as "arena_7 (deleted 2026-11-22 21.30.05)". To get it back, move that folder into world/dimensions/minecraft, rename it to its old name and load_world{ } it. The next start deletes it for good. Works on every world except the main one, also the nether, the end and worlds made by hand or by other plugins.
expert: Thread | main thread; temp is emptied at the next start on a worker thread ("Lullaby world files")
expert: Cost | quick: the folder is only moved (guess 1–10 ms, not measured); its disk space is freed at the next start
expert: Under the hood | the folder is moved whole into plugins/lullaby/temp; the next start deletes everything there
expert: Can fail | an unknown field (the error lists the ones it takes), a bad name, the main world, no such world, the world is being copied right now, a cancelled world_unload, the disk
expert: Reload | nothing to undo
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.delete_world(a)
	needs_table(a, "delete_world{}", 'delete_world{ id = "arena_1" }')
	I.only(a, "delete_world{}", DELETE_TAKES, 2)
	J.world_delete(name_of(a.id, "delete_world{}", "id"), place(a.move_to, "delete_world{}"))
end
