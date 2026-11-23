-- Lullaby library: players and entities, and what only they have (damage, profile, effect). Also the base every
-- object kind shares (I.object_meta, I.object_kind) and I.from_java, the one place Java values become Lua values.
--
-- Objects (player, entity, world, block, chunk) are hand-made: each one exists once (cached), so == works and they
-- can be table keys. Their identity (a UUID, an id, a position) is kept in private tables, never on the object, and
-- every field is read from Java when asked. Reading a name an object doesn't have is an error (I.no_field), never
-- nil. Fields and methods are added only with Kupfermeister's yes. Worlds, blocks, chunks: blocks.lua.
--   player("Notch") / player(uuid text)  -> player or nil (online, or has played here)     p.name  p.uuid  p.online,
--                                           and every entity field (a player is an entity; offline they read nil)
--   entity(uuid text)                    -> entity (or player) or nil if none is loaded    e.uuid e.type e.pos ... (the
--                                           field list is Java's: nms/Entities.java)
--   modify_entity{ entity = e, fire_time = , ai = , food = , display_data = , ... }   changes an entity -> true, false
--                                        if it is gone; fields the entity doesn't have are ignored
--   teleport{ entity = e, location = }   as /tp
--   remove_entity{ entity = e }          removes it (no drops, no death)
--   blueprint{ type = , ...fields }      a value: an entity to make, the same fields as modify_entity; e.blueprint copies
--   summon{ blueprint = b | type = , location = , ...fields }   a real entity
--   clientside_summon{ blueprint = b | type = , location = , player = | players = , persist = , lifetime = , ... }
--                                        a client_entity: only in its viewers' games (nms/ClientEntities.java)
--   damage (from events: e.damage_source)  d.type  d.direct  d.causing  d.pos
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber, raw_next = type, error, setmetatable, tostring, tonumber, next
local rawget = rawget
local gmatch, concat = string.gmatch, table.concat
local meta_of = I.debug_getmetatable

local WEAK_KEYS = { __mode = "k" }
local WEAK_VALUES = { __mode = "v" }
local kind_of = I.kind_of
I.object_meta = {} -- kind -> metatable of each object kind (the wire tags them, see lazy.lua)
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

-- "player", "entity", "world", "block", "chunk" for Lullaby objects, nil for anything else.
function I.object_kind(value)
	return kind_of[value]
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
local CLIENT_HAS = "viewers, " .. ENTITY_HAS

-- What a blueprint holds besides type: modify_entity's fields (Java's list, Entities.java Change), names only.
local BLUEPRINT_FIELDS = {}
for name in gmatch(J.blueprint_fields(), "[%w_]+") do BLUEPRINT_FIELDS[#BLUEPRINT_FIELDS + 1] = name end
local blueprint_of -- (entity) -> blueprint or nil; below

-- level 3: the script line that read the field (entity_field <- __index <- script)
local function entity_field(o, key, kind, has)
	local number = ENTITY_FIELDS[key]
	if not number then I.no_field(kind, key, has, 3) end
	return I.from_java(J.entity_get(slot_of[o], number))
end

-- Entities and players change only through actions (they use the field names): e.velocity = v is an error. Only
-- e.data = { } works (saved data, data.lua).
local function no_assign(letter, is_player)
	return function(o, key, value)
		if key == "data" then return I.set_entity_data(o, is_player, value, 2) end
		-- FORMAT: an entity or player field assigned with = instead of an action
		error("entity fields can't be changed with = (." .. tostring(key) .. "): use an action: modify_entity{ entity = "
			.. letter .. ", ... }, or teleport{ entity = " .. letter .. ", location = ... }", 2)
	end
end

local player_uuid = setmetatable({}, WEAK_KEYS) -- player -> uuid text
local players = setmetatable({}, WEAK_VALUES)   -- uuid text -> player

local PLAYER_META = {
	__index = function(p, key)
		if key == "uuid" then return player_uuid[p] end
		if key == "name" then return J.player_name(slot_of[p]) end
		if key == "online" then return J.player_online(slot_of[p]) end
		if key == "data" then return I.entity_data(p, true, 2) end
		if key == "blueprint" then return nil end -- players can't be made from a blueprint
		return entity_field(p, key, "player", PLAYER_HAS) -- a player is an entity too (nil while offline)
	end,
	__newindex = no_assign("p", true),
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
lead: A player by name or UUID: online, or one who has played on this server before. nil if none. Each player exists once, so == works, online and offline. A player is an entity too: every field of entity( ) works on player (player.pos, player.health, ...) and reads nil while the player is offline; modify_entity{ entity = player, ... } changes an online player.
arg: name | text · player | Name or UUID (a player is returned as is).
returns: player · nil
is: entity
field: uuid | text | The UUID, lowercase with dashes. Also while offline.
field: name | text · nil | The name; for an offline player the one the server last saw (nil if it never saw one).
field: online | boolean | Whether the player is online now.
field: data | table | Saved data of this player, a table that stays like server.data (it takes the same values, see server). It is entity.data, the same field, kept in the player's own file (playerdata/<uuid>.dat in the main world's folder), so it travels with that file. Works online and offline: an offline player's data is read from the file, and a change is written into the file at the next save. A player who never joined has no file yet: then player.data is an error. player.data = { } starts over.
note: player.data reaches the player file when Paper saves the player: at quit, on Paper's timer for each online player (player-auto-save in paper-global.yml), at reload and stop. If something else changes an offline player's file (a tool), the file wins when they join.
example:
  on{ "PlayerJoinEvent", function(event)
    local data = event.player.data
    data.joins = (data.joins or 0) + 1
  end }
  -- also while offline
  local notch = player("Notch")
  if notch then notch.data.coins = (notch.data.coins or 0) + 10 end
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
		if key == "data" then return I.entity_data(e, false, 2) end
		if key == "blueprint" then return blueprint_of(e) end
		return entity_field(e, key, "entity", ENTITY_HAS)
	end,
	__newindex = no_assign("e", false),
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
lead: An entity by its UUID, if it is in a loaded chunk right now: a mob, an item on the ground, an arrow, a minecart, ... (an online player gives the player). nil if there is none. Every sort of entity is one entity: a field a sort doesn't have reads nil (an item has no health). Each entity exists once, so == works, and it stays the same object when its chunk unloads and loads again or it changes world. Entities also come from events (entity.entity, entity.damager, ...), from chunks (context.entities) and from summon{ }. Change one with modify_entity{ } and teleport{ }, remove it with remove_entity{ }.
arg: uuid | text · entity | A UUID like "069a79f4-44e9-4726-a5be-fca90e38aaf5" (an entity or player is returned as is).
returns: entity · player · nil
field: uuid | text | The UUID, lowercase with dashes. Also when the entity is gone.
field: data | table | Saved data of this entity, a table that stays like server.data (it takes the same values, see server). It is kept inside the entity, so it goes where the entity goes (into other worlds too) and is gone with it when it dies or is removed. Only while the entity is loaded: when its chunk unloads, the table is saved into it and locked (any use is a red error that says why); read entity.data again when the entity is back. entity.data = { } starts over. A player's is kept in the player file, offline too: see player( ).
field: blueprint | blueprint · nil | A copy of this entity as a blueprint: its type and every field modify_entity{ } changes (health, custom_name, display_data, ...), to make more like it with summon{ } or clientside_summon{ }. Fields Lullaby doesn't read yet (equipment, effects, other NBT) aren't in it. nil for players and gone entities.
field: type | text | The sort, as vanilla's id: "minecraft:zombie", "minecraft:item".
field: alive | boolean | false once it is dead or gone (removed, in an unloaded chunk, offline).
field: world | world | The world it is in.
field: pos | vec3 | Where its feet are.
field: location | location | World, position and direction (yaw, pitch) at once.
field: velocity | vec3 | Its motion in blocks per tick (vanilla's delta movement). modify_entity{ } changes it.
field: on_ground | boolean | Whether it stands on something.
field: health | number · nil | Health points (2 per heart); nil for entities without health (items, arrows, minecarts, ...). modify_entity{ } changes it.
field: max_health | number · nil | Its highest health; nil for entities without health. modify_entity{ } changes it.
field: absorption | number · nil | Yellow extra hearts, in health points; nil for entities without health. modify_entity{ } changes it.
field: custom_name | component · nil | The name given with a name tag or a command; nil if it has none. modify_entity{ } changes it.
field: custom_name_visible | boolean | Whether the name shows always, not only when someone looks at it up close. modify_entity{ } changes it.
field: vehicle | entity · player · nil | What it rides (a boat, a horse, ...); nil if nothing.
field: glowing | boolean | Whether it has the white outline seen through walls (vanilla's Glowing tag; the glowing effect is apart). modify_entity{ } changes it, and so do all the fields below.
field: silent | boolean | Whether it makes no sounds.
field: gravity | boolean | false: it doesn't fall (vanilla's NoGravity tag).
field: invulnerable | boolean | Whether nothing hurts it except /kill, the void and creative players (vanilla's Invulnerable tag).
field: persistent | boolean · nil | Whether the mob never despawns (vanilla's PersistenceRequired; a name tag sets it too). nil for entities that aren't mobs.
field: visible | boolean | false: invisible, like the potion but without particles; armor and held items still show.
field: collidable | boolean · nil | false: other entities don't push it and it doesn't push them. nil for entities without health.
field: fire_time | number | Ticks it keeps burning; 0 or less: not burning (vanilla counts below 0 after a fire).
field: freeze_time | number | Ticks it has been freezing in powder snow (140 and more: frozen, it takes damage).
field: fall_distance | number | Blocks fallen since it last stood on something; landing hurts from about 3.
field: no_damage_time | number | Ticks it can't be hurt again after a hit (vanilla sets 20 on a hit; above 10 only a harder hit counts).
field: air | number | Air left in ticks (300 is a full bar for most); below 0 it drowns.
field: ai | boolean · nil | false: the mob does nothing at all, not even fall or turn its head. nil for entities that aren't mobs (players, items, ...).
field: aware | boolean · nil | false: the mob makes no own choices (no walking, no attacking) but still falls and gets pushed. nil if no mob.
field: target | entity · player · nil | Who the mob is after; nil if nobody or no mob. Mobs with a brain (villagers, piglins, wardens, ...) may pick their own again.
field: can_pickup_items | boolean · nil | Whether the mob picks up items and armor from the ground. nil if no mob.
field: angry | boolean · nil | Whether a mob that can turn angry is angry right now (wolves, bees, endermen, zombified piglins, iron golems, polar bears, ...); nil for other entities.
field: left_handed | boolean · nil | Whether the mob holds and swings with its left hand. nil if no mob.
field: food | number · nil | Player's food bar, 0 to 20 (each drumstick is 2). nil for other entities.
field: saturation | number · nil | Player's hidden food buffer, used up before the food bar. nil if no player.
field: exhaustion | number · nil | Player's hidden hunger counter: at 4 it takes one saturation or food point and starts over. nil if no player.
field: xp | number · nil | Player's XP points inside the current level (the green bar). nil if no player.
field: xp_level | number · nil | Player's XP level (the green number). nil if no player.
field: display_data | table · nil | Everything a display entity (text_display, item_display, block_display) shows, as one table: see display_data. Your own copy; nil for entities that are no display. modify_entity{ } changes it.
note: A gone entity (dead, removed, in an unloaded chunk) reads nil everywhere except uuid and alive (false); it comes back by itself when its chunk loads again. Fields are read from the game each time: keep the value (local pos = entity.pos) when you need it twice. A name an entity doesn't have at all (entity.helth) is an error that lists the fields.
example:
  local target = entity("069a79f4-44e9-4726-a5be-fca90e38aaf5")
  if target and target.alive then print(target.type, target.pos, target.health) end
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

-- The entity actions below: Java checks the names and values (Entities.java owns what each takes). Lua checks only what
-- Java can't see: the entity object (sent as its slot number), and objects inside the values (a location's world, a
-- target), since an object's identity doesn't cross the wire.
-- level 3: the script line (entity_action <- the action <- script)
local function entity_action(name, a, example)
	-- FORMAT: an entity action called without { }
	if type(a) ~= "table" then error(name .. " needs { }: " .. example, 3) end
	local e = a.entity
	local kind = kind_of[e]
	-- FORMAT: an entity action without an entity, player or client_entity
	if kind ~= "entity" and kind ~= "player" and kind ~= "client_entity" then
		error(name .. "{}: entity must be an entity, a player or a client_entity, got " .. I.type_name(e), 3)
	end
	local t = {}
	for key, value in raw_next, a do t[key] = value end
	t.entity = slot_of[e]
	return t
end

--[[doc
name: modify_entity{ }
kind: doing
group: Entities
words: set change modify adjust health heal hearts kill max absorption yellow golden velocity motion push launch throw knockback name nametag glow outline silent sound gravity float invulnerable god despawn persistent invisible hidden collide push fire burn freeze fall air drown ai brain aware target attack pickup angry left hand food hunger saturation exhaustion xp experience level display text hologram interpolate scale rotate
syntax: modify_entity{ entity = entity | player | client_entity, health = number, velocity = vec3, glowing = boolean, ai = boolean, food = number, display_data = table, ... }
lead: Changes an entity, an online player or a client_entity. Give the entity and one or more of the fields below: the same names as the fields you read (entity.health, entity.velocity, entity.ai). Every value is checked before anything changes. A field this entity doesn't have (food on a zombie, ai on an item, display_data on a pig) is ignored, so one call can go to entities of every sort; a name no entity has (helth) is an error. Teleporting has its own action: teleport{ }. The same fields go into blueprint{ }, summon{ } and clientside_summon{ }.
arg: entity | entity · player · client_entity | What to change. On a client_entity only what players see changes anything (ai, target and the like do nothing there; target is ignored).
arg: max_health? | number | Entities with health only. Its highest health (the max_health attribute; vanilla allows 1 to 1024). Health above it drops to it. Changes before health, so both can be given at once.
arg: health? | number | Entities with health only. Its health, kept between 0 and max_health as vanilla does; 0 kills it. It is no hit: no armor, no knockback, no red flash (hurt{ } will do that). To add some: health = entity.health + 4.
arg: absorption? | number | Entities with health only. Yellow extra hearts in health points (4 is two hearts), 0 or more; 0 removes them. They stay until hits use them up, as with a golden apple.
arg: velocity? | vec3 | Its motion in blocks per tick (1 block per tick is 20 blocks per second); it replaces the motion it had, then air and gravity slow it as usual.
arg: custom_name? | text · component · false | The name above it: text is shown exactly as written, a component ({ text = "Boss", color = "red" }) can have colors; false removes it. A player's own name doesn't change (only what some plugins show).
arg: custom_name_visible? | boolean | true: the name shows always, not only up close.
arg: glowing? | boolean | The white outline seen through walls (its color follows the scoreboard team).
arg: silent? | boolean | true: it makes no sounds.
arg: gravity? | boolean | false: it doesn't fall and stays in the air.
arg: invulnerable? | boolean | true: nothing hurts it except /kill, the void and creative players.
arg: persistent? | boolean | Mobs only. true: never despawns.
arg: visible? | boolean | false: invisible, like the potion but without particles.
arg: collidable? | boolean | Entities with health only. false: no pushing between it and other entities (a player's own game may still push it a little).
arg: fire_time? | number | Ticks it keeps burning (20 is one second); 0 puts it out.
arg: freeze_time? | number | Ticks of powder-snow freezing; 140 and more freezes it (blue hearts, damage). It thaws by itself out of powder snow.
arg: fall_distance? | number | Blocks it has fallen so far: 0 saves it from fall damage, 20 makes the next landing hurt as from 20 blocks.
arg: no_damage_time? | number | Ticks it can't be hurt again; 0 lets the next hit through at once (fast hits, no cooldown).
arg: air? | number | Air left in ticks; 300 is a full bar for most, below 0 it drowns.
arg: ai? | boolean | Mobs only. false: it does nothing at all (no moving, falling or turning), like vanilla's NoAI tag.
arg: aware? | boolean | Mobs only. false: no own choices (walking, attacking), but it still falls and can be pushed.
arg: target? | entity · player · false | Mobs only. Who it goes after; false: nobody. Must be a living entity. Mobs with a brain (villagers, piglins, wardens, ...) may pick their own again.
arg: can_pickup_items? | boolean | Mobs only. Whether it picks up items and armor from the ground.
arg: angry? | boolean | Mobs that can turn angry only (wolves, bees, endermen, zombified piglins, iron golems, polar bears, ...). true: angry for vanilla's usual time; false: calm at once.
arg: left_handed? | boolean | Mobs only. Holds and swings with the left hand.
arg: food? | number | Players only. The food bar, a whole number; 20 is full.
arg: saturation? | number | Players only. The hidden food buffer (vanilla goes up to the food level).
arg: exhaustion? | number | Players only. The hidden hunger counter (at 4 it costs a point).
arg: xp? | number | Players only. XP points inside the current level, as /xp set ... points: kept between 0 and what the level needs.
arg: xp_level? | number | Players only. The XP level, as /xp set ... levels.
arg: display_data? | table | Display entities only (text_display, item_display, block_display). What it shows, how big, turned, lit, animated: see display_data. Only the entries given change.
returns: boolean (false if the entity is gone)
example:
  -- throw entity up and towards player
  modify_entity{ entity = entity, velocity = (player.pos - entity.pos):normalize() * 0.5 + vec3(0, 0.5, 0) }
  -- a boss with 100 hearts, all full
  modify_entity{ entity = boss, max_health = 200, health = 200 }
  -- freeze a mob in place and put out the fire
  modify_entity{ entity = mob, ai = false, fire_time = 0 }
  -- a glowing boss that never despawns
  modify_entity{ entity = boss, custom_name = { text = "Boss", color = "red" }, custom_name_visible = true, glowing = true, persistent = true }
  -- a full food bar
  modify_entity{ entity = player, food = 20, saturation = 5 }
  -- grow a display to twice its size over one second, smoothly on screen
  modify_entity{ entity = sign, display_data = { transformation = { scale = vec3(2, 2, 2) }, interpolation = { duration = 20, start = 0 } } }
expert: Thread | main thread, like every script
expert: Cost | 5–30 µs (guess, not measured)
expert: Under the hood | one Lua → Java call; health: vanilla setHealth; max_health: the attribute's base value; absorption: vanilla keeps it under the max_absorption attribute (0 unless an effect raises it), so Lullaby raises that attribute's base just enough first; velocity: vanilla setDeltaMovement plus the flag knockback sets (markHurt), so every client gets it, the pushed player's own too; the other fields are vanilla's own setters (setCustomName, setGlowingTag, setNoGravity, setPermanentlyInvulnerable, setInvisible, setRemainingFireTicks, setTicksFrozen, setNoAi, Mob.setTarget, NeutralMob.startPersistentAngerTimer / stopBeingAngry, FoodData, setExperiencePoints, setExperienceLevels, ...); the client gets the change the way vanilla sends it; a client_entity's viewers get one bundle with only the changed values (nms/ClientEntities.java)
expert: Can fail | an unknown field (the error lists the ones it takes), nothing to change, a value of the wrong kind, absorption below 0, a target that is gone or not living (a field the entity doesn't have is ignored, no error)
expert: Reload | changes stay
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.modify_entity(a)
	local t = entity_action("modify_entity", a, "modify_entity{ entity = mob, ai = false }")
	local target = a.target
	if target ~= nil and target ~= false then
		local kind = kind_of[target]
		-- FORMAT: modify_entity{} target that isn't an entity, a player or false
		if kind ~= "entity" and kind ~= "player" then
			error("modify_entity{}: target must be an entity, a player or false, got " .. I.type_name(target), 2)
		end
		t.target = slot_of[target]
	end
	return J.entity_modify(I.wire(t))
end

--[[doc
name: teleport{ }
kind: doing
group: Entities
words: tp move warp location place world travel
syntax: teleport{ entity = entity | player | client_entity, location = location }
lead: Teleports an entity or an online player, as /tp does, also into another world: its fall speed is reset unless it is gliding, and a mob stops walking its path. A client_entity moves on its viewers' screens; a display glides there over its display_data teleport_duration.
arg: entity | entity · player · client_entity | What to teleport.
arg: location | location | Where to, with the direction it then looks (yaw, pitch).
returns: boolean (false if the entity is gone or the teleport was refused, for example by another plugin)
example:
  teleport{ entity = player, location = location(world("overworld"), vec3(0.5, 70, 0.5)) }
expert: Thread | main thread, like every script
expert: Cost | 5–30 µs, into another world more (guess, not measured)
expert: Under the hood | one Lua → Java call; vanilla teleportTo, the same steps as /tp
expert: Can fail | an unknown field, location missing or not a location, a location outside the world (/tp refuses it too) or in a world that isn't loaded
expert: Reload | changes stay
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.teleport(a)
	local t = entity_action("teleport", a, "teleport{ entity = player, location = place }")
	local l = a.location
	if l ~= nil then
		-- FORMAT: teleport{} location of the wrong kind
		if I.type_name(l) ~= "location" then error("teleport{}: location must be a location, got " .. I.type_name(l), 2) end
		local pos, rot = l[2], l[3]
		t.location = { slot_of[l[1]], pos[1], pos[2], pos[3], rot[1], rot[2] }
	end
	return J.entity_teleport(I.wire(t))
end

--[[doc
name: remove_entity{ }
kind: doing
group: Entities
words: remove delete despawn kill discard destroy clear hide
syntax: remove_entity{ entity = entity | client_entity }
lead: Removes an entity from the world, as if it had never been there: no death, no drops, no death event. A client_entity disappears from every viewer's screen. Players can't be removed.
arg: entity | entity · client_entity | What to remove.
returns: boolean (false if it was gone already)
example:
  local zombie = summon{ type = "zombie", location = place }
  wait(100)
  remove_entity{ entity = zombie }
expert: Thread | main thread, like every script
expert: Cost | 2–20 µs (guess, not measured)
expert: Under the hood | one Lua → Java call; vanilla Entity.discard (Paper's EntityRemoveEvent with cause PLUGIN); a client_entity: packet Remove Entities (play, clientbound) to its online viewers
expert: Can fail | an unknown field, entity not an entity or client_entity, a player
expert: Reload | a removed entity stays removed
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.remove_entity(a)
	local t = entity_action("remove_entity", a, "remove_entity{ entity = e }")
	-- FORMAT: remove_entity{} of a player
	if kind_of[a.entity] == "player" then error("remove_entity{}: players can't be removed", 2) end
	return J.entity_remove(I.wire(t))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Blueprints (a value: an entity to make; summon{ } and clientside_summon{ } make it)
-- ---------------------------------------------------------------------------------------------------------------

-- A blueprint keeps its fields in a private table (bp_fields), never on the object, so it can't be changed. Reading a
-- field gives a copy of tables (display_data, a component) and item stacks, so changing what you read doesn't change
-- the blueprint either.
local bp_fields = setmetatable({}, WEAK_KEYS) -- blueprint -> { type = , field = value, ... }
local BP_KNOWN = { type = true }
for i = 1, #BLUEPRINT_FIELDS do BP_KNOWN[BLUEPRINT_FIELDS[i]] = true end
local BP_HAS = "type, " .. concat(BLUEPRINT_FIELDS, ", ")

-- A copy that can't change the original: plain tables and components are copied deeply, item stacks copied; values
-- (vec3, quaternion, block_state, ...) can't change anyway and stay as they are.
local function copy(v)
	if type(v) ~= "table" then return v end
	local meta = meta_of(v)
	local kind = meta and I.kind_by_meta[meta]
	if kind == "item_stack" then return lib.item_stack(v) end
	if meta ~= nil and kind ~= "component" then return v end
	local t = {}
	for key, value in raw_next, v do t[key] = copy(value) end
	return setmetatable(t, meta)
end

-- == for blueprints: plain tables and components by content, everything else with ==.
local function same(a, b)
	if a == b then return true end
	if type(a) ~= "table" or type(b) ~= "table" then return false end
	local meta = meta_of(a)
	if meta ~= meta_of(b) or (meta ~= nil and I.kind_by_meta[meta] ~= "component") then return false end
	for key, value in raw_next, a do if not same(value, rawget(b, key)) then return false end end
	for key in raw_next, b do if rawget(a, key) == nil then return false end end
	return true
end

local BLUEPRINT = {
	__index = function(b, key)
		if BP_KNOWN[key] then return copy(bp_fields[b][key]) end
		I.no_field("blueprint", key, BP_HAS, 2)
	end,
	-- FORMAT: a blueprint field assigned
	__newindex = function(_, key)
		error("blueprints can't be changed (." .. tostring(key) .. "): make a new one: blueprint{ from = b, "
			.. tostring(key) .. " = ... }", 2)
	end,
	__tostring = function(b) return "blueprint " .. (bp_fields[b].type:gsub("^minecraft:", "")) end, -- FORMAT
	__eq = function(a, b)
		if meta_of(a) ~= meta_of(b) then return false end
		return same(bp_fields[a], bp_fields[b])
	end,
	__metatable = false,
}
I.kind_by_meta[BLUEPRINT] = "blueprint"

local function new_blueprint(t)
	local b = setmetatable({}, BLUEPRINT)
	bp_fields[b] = t
	return b
end

-- entity.blueprint: the type and every blueprint field, read like a script reads them (nil for a gone entity).
function blueprint_of(o)
	local entity_type = o.type
	if entity_type == nil then return nil end
	local t = { type = entity_type }
	for i = 1, #BLUEPRINT_FIELDS do
		local name = BLUEPRINT_FIELDS[i]
		t[name] = o[name]
	end
	return new_blueprint(t)
end

--[[doc
name: blueprint{ }
kind: reading
group: Entities
words: template prefab copy clone entity spawn make prototype preset
syntax: blueprint{ type = text, health = number, glowing = boolean, display_data = table, ... } → blueprint   blueprint{ from = blueprint, ... } → blueprint   entity.blueprint → blueprint
lead: An entity to make, as a value: its type and the same fields modify_entity{ } takes. Make it with summon{ } (a real entity) or clientside_summon{ } (only some players see it), as often as you like. Everything is checked here, so a wrong field shows on this line, not later. A blueprint can't be changed: blueprint{ from = b, glowing = true } makes a new one from b with glowing changed. entity.blueprint copies an existing entity (not players).
arg: type | text | Needed (unless from is given). The entity id, as /summon takes it: "zombie", "minecraft:text_display". Players and the few kinds /summon can't make (fishing bobbers, ...) are refused.
arg: from? | blueprint | Starts from this blueprint; the other fields given change it.
arg: (fields)? | any | Any field of modify_entity{ } except target (a blueprint holds no other entities): max_health, health, absorption, velocity, custom_name, custom_name_visible, glowing, silent, gravity, invulnerable, persistent, visible, collidable, fire_time, freeze_time, fall_distance, no_damage_time, air, ai, aware, can_pickup_items, angry, left_handed, food, saturation, exhaustion, xp, xp_level, display_data. A field the type doesn't have is ignored.
returns: blueprint
field: type | text | The full id: "minecraft:zombie".
field: (fields) | any · nil | Each field as it was given (nil if not): bp.glowing, bp.display_data. Tables and item stacks you read are your own copies.
example:
  local popup = blueprint{
    type = "text_display",
    display_data = { billboard = "center", text = { text = { text = "+10 coins", color = "gold" }, background = 0 } },
  }
  local big = blueprint{ from = popup, display_data = { transformation = { scale = vec3(2, 2, 2) } } }
  -- a copy of a mob, made again somewhere else
  local twin = summon{ blueprint = mob.blueprint, location = place }
expert: Thread | main thread, like every script
expert: Cost | blueprint{ }: 20–200 µs (vanilla makes a throwaway entity to check the fields on; guess, not measured); entity.blueprint: 30–150 µs (one read per field); reading a field: plain Lua
expert: Under the hood | nms/Entities.java blueprintCheck: vanilla EntityType.create (never added to a world) and the same checks as modify_entity{ }; the blueprint itself is a plain Lua value, summon{ } sends its fields again
expert: Can fail | no type, an unknown or unmakeable type, an unknown field (the error lists the ones it takes), a value of the wrong kind, target (not in blueprints)
expert: Reload | blueprints are plain Lua values: they go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.blueprint(a)
	-- FORMAT: blueprint without { }
	if type(a) ~= "table" or meta_of(a) ~= nil then
		error("blueprint needs { }: blueprint{ type = \"zombie\", glowing = true }", 2)
	end
	local t = {}
	local from = a.from
	if from ~= nil then
		-- FORMAT: blueprint from that is no blueprint
		if meta_of(from) ~= BLUEPRINT then error("blueprint{}: from must be a blueprint, got " .. I.type_name(from), 2) end
		for key, value in raw_next, bp_fields[from] do t[key] = value end
	end
	for key, value in raw_next, a do
		if key ~= "from" then t[key] = value end
	end
	-- Java checks the type and every field (also unknown names) and gives the full id back
	t.type = J.blueprint_check(I.wire(t))
	for key, value in raw_next, t do t[key] = copy(value) end -- its own copies: the script's tables may change later
	return new_blueprint(t)
end

-- summon{ } and clientside_summon{ }: the wire table with the blueprint's fields (the given ones win), the type, and
-- the location as { world slot, x, y, z, yaw, pitch }. level 3: the script line (spawn_table <- the action <- script).
local function spawn_table(a, name, example)
	-- FORMAT: summon without { }
	if type(a) ~= "table" then error(name .. " needs { }: " .. example, 3) end
	local b = a.blueprint
	-- FORMAT: summon with both or neither of blueprint and type
	if (b == nil) == (a.type == nil) then error(name .. "{}: give blueprint = b or type = \"zombie\" (one of them)", 3) end
	local t = {}
	if b ~= nil then
		-- FORMAT: summon blueprint that is no blueprint
		if meta_of(b) ~= BLUEPRINT then error(name .. "{}: blueprint must be a blueprint, got " .. I.type_name(b), 3) end
		for key, value in raw_next, bp_fields[b] do t[key] = value end
	end
	for key, value in raw_next, a do
		if key ~= "blueprint" then t[key] = value end
	end
	local l = a.location
	if l ~= nil then
		-- FORMAT: summon location of the wrong kind
		if I.type_name(l) ~= "location" then error(name .. "{}: location must be a location, got " .. I.type_name(l), 3) end
		local pos, rot = l[2], l[3]
		t.location = { slot_of[l[1]], pos[1], pos[2], pos[3], rot[1], rot[2] }
	end
	return t
end

--[[doc
name: summon{ }
kind: doing
group: Entities
words: spawn create make mob entity place new
syntax: summon{ blueprint = blueprint | type = text, location = location, ...fields } → entity | nil
lead: Makes a real entity, as /summon does: a mob gets vanilla's random spawn setup (equipment, baby or not, ...), then the blueprint's fields, then it enters the world and Paper's spawn events fire (your own on{ "CreatureSpawnEvent", ... } handlers run inside summon{ }). Give a blueprint or just a type. Any field of blueprint{ } may be given here too; it wins over the blueprint's.
arg: blueprint / type | blueprint · text | Needed, one of the two: what to make. type is an entity id like "zombie".
arg: location | location | Where, and which way it looks.
arg: (fields)? | any | Any field of blueprint{ } (modify_entity{ }'s fields except target). A field the type doesn't have is ignored.
returns: entity · nil (nil if the spawn was refused: a plugin or event cancelled it, or the world is on peaceful and the mob is hostile)
example:
  local guard = summon{ type = "iron_golem", location = place, custom_name = "Guard", persistent = true }
  local popup = blueprint{ type = "text_display", display_data = { text = { text = "Hello" } } }
  summon{ blueprint = popup, location = place }
expert: Thread | main thread, like every script
expert: Cost | 30–300 µs, more for mobs with much setup (guess, not measured)
expert: Under the hood | one Lua → Java call; vanilla EntityType.create, Mob.finalizeSpawn (as /summon), the changes of modify_entity{ }, ServerLevel.tryAddFreshEntityWithPassengers with spawn reason CUSTOM
expert: Can fail | both or neither of blueprint and type, an unknown or unmakeable type, no location, a location outside the world or in a world that isn't loaded, an unknown field, a value of the wrong kind
expert: Reload | the entity stays (it is a real entity)
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.summon(a)
	local t = spawn_table(a, "summon", "summon{ type = \"zombie\", location = place }")
	return I.from_java(J.entity_summon(I.wire(t)))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Clientside entities (only in the games of their viewers; nms/ClientEntities.java)
-- ---------------------------------------------------------------------------------------------------------------

local client_uuid = setmetatable({}, WEAK_KEYS) -- client_entity -> uuid text

local CLIENT_META = {
	__index = function(c, key)
		if key == "uuid" then return client_uuid[c] end
		if key == "viewers" then
			local list = {}
			for uuid in gmatch(J.client_viewers(slot_of[c]), "[^\n]+") do list[#list + 1] = I.player_by_uuid(uuid) end
			return list
		end
		if key == "blueprint" then return blueprint_of(c) end
		-- FORMAT: client_entity.data
		if key == "data" then
			error("a client_entity has no data: it is in no world, only in its viewers' games (keep your own table, e.g. by its uuid)", 2)
		end
		return entity_field(c, key, "client_entity", CLIENT_HAS)
	end,
	-- FORMAT: a client_entity field assigned with =
	__newindex = function(_, key)
		error("client_entity fields can't be changed with = (." .. tostring(key) .. "): use modify_entity{ entity = c, ... } or teleport{ entity = c, location = ... }", 2)
	end,
	-- FORMAT: a client_entity as text: "client_entity text_display <uuid>", "(gone)" once removed
	__tostring = function(c)
		local t = c.type
		return "client_entity " .. (t and (t:gsub("^minecraft:", "")) or "(gone)") .. " " .. client_uuid[c]
	end,
	__metatable = false,
}
I.object_meta.client_entity = CLIENT_META

-- The viewers as slots (effects.lua's receivers: offline players and an empty list are fine). Errors at level 4:
-- receivers <- viewers <- clientside_summon <- script (not a tail call, which would drop this level).
local function viewers(a)
	local one, list = I.receivers(a, "clientside_summon{}")
	return one, list
end

--[[doc
name: clientside_summon{ }
kind: doing
group: Entities
words: fake packet client hologram display text floating particle only player visible per-player private
syntax: clientside_summon{ blueprint = blueprint | type = text, location = location, player = player | players = { player, ... }, persist = boolean | { chunks = boolean, worlds = boolean, rejoin = boolean }, lifetime = number, ...fields } → client_entity
lead: Makes an entity that exists only in the games of its viewers: floating text, items and blocks for one player, animated displays, markers. The server has no such entity, so it can't be hit, never moves by itself, touches nothing, and costs the server nothing per tick. Change it with modify_entity{ } and teleport{ } (displays animate smoothly with display_data's interpolation and teleport_duration), play sounds at it with sound{ }, remove it with remove_entity{ }. Viewers who are offline or in another world see nothing (no error); the list stays as given.
arg: blueprint / type | blueprint · text | Needed, one of the two: what to make. type is an entity id like "text_display". Any type /summon can make works; display entities are made for this.
arg: location | location | Where, and which way it looks.
arg: player / players | player · list of player | Who sees it. Exactly one of the two; offline players and an empty list are fine.
arg: persist? | boolean · table | Shows it again when a viewer's game forgot it. A player's game forgets entities by itself in three cases: chunks (the viewer walked away until its chunk unloaded, then came back), worlds (the viewer died and respawned, or changed world and came back), rejoin (the viewer quit and joined again). true: all three; a table picks some ({ chunks = true }); false or missing (default): sent once, gone for good in those cases (cheapest, for short effects).
arg: lifetime? | number | Ticks until Lullaby removes it by itself (20 ticks are a second). Default 12000 (10 minutes); -1: never (until remove_entity{ } or a reload).
arg: (fields)? | any | Any field of blueprint{ }; it wins over the blueprint's. Fields only the server uses (ai, aware, persistent, can_pickup_items, ...) do nothing here.
returns: client_entity
note: /lullaby reload removes every client_entity from every screen. The config's clientside_entity_limit (default 1000000) is the most that may exist at once, for all scripts together; past it clientside_summon{ } is an error.
example:
  local popup = clientside_summon{
    type = "text_display", location = player.location:offset(vec3(0, 2.5, 0)), player = player, lifetime = 40,
    display_data = { billboard = "center", teleport_duration = 20, text = { text = { text = "+10 coins", color = "gold" } } },
  }
  teleport{ entity = popup, location = player.location:offset(vec3(0, 3.5, 0)) } -- floats up for a second
  sound{ player = player, sound = "entity.experience_orb.pickup", source = "master", entity = popup }
expert: Thread | main thread, like every script
expert: Cost | 20–200 µs to make (guess, not measured); per tick nothing; the server keeps one vanilla entity per client_entity in memory (about 1–3 KB, guess)
expert: Under the hood | nms/ClientEntities.java: a vanilla entity made with EntityType.create and never added to a world; vanilla's own spawn packets (ServerEntity.sendPairingData: Spawn Entity, Set Entity Metadata, ...), later only the changed metadata (SynchedEntityData.packDirty), Teleport Entity, Remove Entities (play, clientbound); persist listens to PlayerChunkLoadEvent, PlayerPostRespawnEvent, PlayerChangedWorldEvent and PlayerJoinEvent, only while a client_entity persists
expert: Can fail | both or neither of blueprint and type, an unknown or unmakeable type, no location, a location outside the world or in a world that isn't loaded, neither player nor players, a lifetime that is not -1 or at least 1, the config's clientside_entity_limit, an unknown field, a value of the wrong kind
expert: Reload | every client_entity is removed from every screen
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.clientside_summon(a)
	local t = spawn_table(a, "clientside_summon",
		"clientside_summon{ type = \"text_display\", location = place, player = player }")
	t.player, t.players = viewers(a)
	local uuid, slot = J.client_summon(I.wire(t), I.config.clientside_entity_limit or -1)
	local c = setmetatable({}, CLIENT_META)
	client_uuid[c] = uuid
	kind_of[c] = "client_entity"
	I.give_slot(c, slot)
	return c
end

--[[doc
id: client_entity
name: client_entity
kind: type
group: Entities
words: fake clientside hologram display packet
syntax: client.uuid   client.type   client.alive   client.pos   client.viewers   client.display_data   ...
lead: An entity only some players see, from clientside_summon{ }. It reads like an entity (every field of entity( ): pos, custom_name, glowing, display_data, ...), giving what the server last sent its viewers. modify_entity{ }, teleport{ }, remove_entity{ } and sound{ } take it. After remove_entity{ }, the end of its lifetime or a reload, alive is false and the other fields read nil (uuid stays); changing it then does nothing and returns false.
is: entity
field: viewers | list of player | Who sees it: the players given to clientside_summon{ } (also those offline now).
field: data | error | An error: it is in no world, so it has no saved data.
note: It is in no world: no events fire for it, chunks don't list it, entity( uuid ) doesn't find it, and real mobs can't target it (target = it is an error). Keep the object you got back.
expert: Java object | a ClientEntities.Fake in a slot: the vanilla entity (never in a world), its viewers, persist, lifetime
expert: Stays valid | until removed (remove_entity{ }, its lifetime, a reload); then alive is false
expert: Compare | == is the same object (each exists once)
expert: Cost | a field read 1–5 µs (guess, not measured)
status: unconfirmed
]]

--[[doc
id: display_data
name: display_data
kind: type
group: Entities
words: display text item block hologram transformation scale rotation translation interpolate interpolation animate billboard brightness glow shadow
syntax: display_data = { transformation = { ... }, interpolation = { ... }, teleport_duration = , billboard = , brightness = , view_range = , shadow = { ... }, size = { ... }, glow_color = , text = { ... }, item = { ... }, block_state = }
lead: Everything a display entity (text_display, item_display, block_display) shows, as one table: modify_entity{ display_data = ... }, blueprint{ display_data = ... }, summon{ }, clientside_summon{ }, and entity.display_data reads it back in the same shape. Every entry is optional and changes only itself: display_data = { transformation = { scale = v } } keeps the rotation. An entry the display doesn't have (text on a block display) is ignored. Names are vanilla's own (as in the display's NBT), with the parts that belong together in inner tables.
field: transformation | table | Moves, turns and scales the shown thing around the entity's position, without moving the entity: { translation = vec3 (offset), left_rotation = quaternion, scale = vec3, right_rotation = quaternion (applied before scale; left after) }. Defaults: no offset, no turn, scale 1.
field: interpolation | table | Animates a transformation change on screen: { duration = ticks it takes, start = ticks from now it begins (0: at once) }. Give start every time an animation should begin (even the same number: each start begins anew), with the new transformation in the same call.
field: teleport_duration | number | Ticks a teleport{ } takes on screen (0 to 59): the display glides there. Default 0 (jumps).
field: billboard | text | Which way it faces: "fixed" (its own rotation, default), "vertical" (turns left and right to the viewer), "horizontal", "center" (always faces the viewer).
field: brightness | table · false | Its own light: { block = 0 to 15, sky = 0 to 15 } (both needed); false: the world's light (default).
field: view_range | number | How far it is drawn, times the normal entity distance. Default 1.
field: shadow | table | { radius = blocks, strength = 0 to 1 }. Default no shadow.
field: size | table | { width = , height = } of its culling box: it isn't drawn when that box is off screen. 0 (default): never culled.
field: glow_color | number · false | The color of its glowing outline (with glowing = true) as one RGB number: 0xFF0000 is red. false: the team color (default).
field: text | table | Text displays: { text = text or component (what it shows, literal), line_width = pixels before a line breaks (default 200), background = ARGB number (0x40000000 is vanilla's see-through black; 0: none), opacity = 0 to 255 (255 default), shadow = boolean, see_through = boolean (seen through blocks), default_background = boolean (the chat's background), alignment = "center" | "left" | "right" }.
field: item | table | Item displays: { item = item_stack or item text ("diamond_sword[damage=5]"), display = how it is held: "none" (default), "thirdperson_lefthand", "thirdperson_righthand", "firstperson_lefthand", "firstperson_righthand", "head", "gui", "ground", "fixed" }.
field: block_state | block_state · text | Block displays: the block it shows ("oak_stairs[facing=east]").
example:
  modify_entity{ entity = sign, display_data = {
    transformation = {
      translation    = vec3(0, 0.5, 0),
      scale          = vec3(2, 2, 2),
      left_rotation  = quaternion.from_axis_angle(vec3(0, 1, 0), 90),
      right_rotation = quaternion(0, 0, 0, 1),
    },
    interpolation     = { duration = 20, start = 0 },
    teleport_duration = 3,
    billboard         = "center",
    brightness        = { block = 15, sky = 15 },
    view_range        = 1.5,
    shadow            = { radius = 0.5, strength = 1 },
    size              = { width = 0, height = 0 },
    glow_color        = 0xFF00FF,
    text = {
      text = { text = "Hello", color = "gold", bold = true },
      line_width = 200, background = 0x40000000, opacity = 255,
      shadow = true, see_through = false, default_background = false, alignment = "center",
    },
    item        = { item = "minecraft:diamond_sword", display = "fixed" }, -- item displays only
    block_state = "minecraft:oak_stairs[facing=east]",                    -- block displays only
  } }
expert: Java object | none kept: read from the display entity's data each time (entity.display_data is your own table)
expert: Stays valid | always (a copy)
expert: Compare | a plain table: compare its entries
expert: Cost | reading 10–50 µs, changing 5–40 µs (guess, not measured)
status: unconfirmed
]]

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
syntax: damage.type   damage.direct   damage.causing   damage.pos
lead: What hurt an entity (vanilla's damage source), from events: event.damage_source in EntityDamageEvent, EntityDeathEvent, ... A value: your own copy, it can't be changed.
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

local PROFILE = I.value_type("profile", { "name", "uuid", "textures", "textures_signature" }, {})
-- FORMAT: a profile as text
PROFILE.__tostring = function(p) return "profile " .. tostring(rawget(p, 1) or rawget(p, 2)) end

--[[doc
id: profile
name: profile
kind: type
group: Players
words: game profile skin textures head
syntax: profile.name   profile.uuid   profile.textures
lead: A player's profile as the server knows it (name, UUID, skin), from events: event.player_profile in PlayerServerFullCheckEvent, ProfileWhitelistVerifyEvent, ... A value: your own copy, it can't be changed.
field: name | text · nil | The name; nil if the profile has none.
field: uuid | text · nil | The UUID, lowercase with dashes; nil if the profile has none.
field: textures | text · nil | The skin: the "textures" property's value (base64 text, as Mojang sends it); nil if none.
field: textures_signature | text · nil | Mojang's signature of textures; nil if unsigned.
expert: Java object | none kept: a plain Lua value; other profile properties than textures aren't shown yet
expert: Stays valid | always (a copy)
expert: Compare | == compares all four fields
expert: Cost | made when the event field is read: 2–10 µs (guess, not measured)
status: unconfirmed
]]
function I.new_profile(name, uuid, textures, signature)
	return setmetatable({ name, uuid, textures, signature }, PROFILE)
end

local EFFECT = I.value_type("effect", { "id", "amplifier", "duration", "ambient", "show_particles", "show_icon" }, {})
-- FORMAT: an effect as text
EFFECT.__tostring = function(e) return "effect " .. e[1] .. " " .. e[2] end

--[[doc
id: effect
name: effect
kind: type
group: Entities
words: potion status buff debuff mob effect speed poison amplifier level
syntax: effect.id   effect.amplifier   effect.duration   effect.ambient   effect.show_particles   effect.show_icon
lead: A status effect as an entity has it (vanilla's mob effect: speed, poison, ...), from events: event.old_effect and event.new_effect in EntityPotionEffectEvent, event.effect in BeaconEffectEvent. A value: your own copy, it can't be changed.
field: id | text | The effect's id: "minecraft:speed", "minecraft:poison", ...
field: amplifier | number | The level minus 1, as in /effect: 0 is Speed I, 1 is Speed II.
field: duration | number | Seconds left (ticks / 20, so it can be 2.5); math.huge for an infinite effect.
field: ambient | boolean | Whether it comes from a beacon or conduit (fainter particles).
field: show_particles | boolean | Whether it shows particles.
field: show_icon | boolean | Whether it shows its icon in the top right.
example:
  on{ "EntityPotionEffectEvent", function(event)
    if event.new_effect and event.new_effect.id == "minecraft:poison" then event.cancelled = true end
  end }
expert: Java object | none kept: a plain Lua value
expert: Stays valid | always (a copy)
expert: Compare | == compares all six fields
expert: Cost | made when the event field is read: 2–10 µs (guess, not measured)
status: unconfirmed
]]
function I.new_effect(id, amplifier, ticks, ambient, particles, icon)
	return setmetatable({ id, amplifier, ticks < 0 and math.huge or ticks / 20, ambient, particles, icon }, EFFECT)
end

-- A "tree" from Java (Codecs.treeOut: a table of values, e.g. entity.display_data): the marked tables
-- { [true] = kind, ... } become the Lua values they stand for, everything else stays a plain table.
local TREE_VALUES = {
	vec3 = function(t) return I.new_vec3(t[1], t[2], t[3]) end,
	quaternion = function(t) return I.new_quaternion(t[1], t[2], t[3], t[4]) end,
	block_state = function(t) return I.block_state_by_id(t[1]) end,
	item_stack = function(t) return I.new_item(t[1]) end,
	component = function(t) return I.as_component(t[1]) end,
}
local function tree(t)
	for key, value in raw_next, t do
		if type(value) == "table" then
			local kind = rawget(value, true)
			if type(kind) == "string" then t[key] = TREE_VALUES[kind](value) else tree(value) end
		end
	end
	return t
end

-- Java hands a value over as a kind word plus values (ToLua.java; "v" is a plain value, "c" a component, EventBridge
-- adds "v" for enums, keys and UUIDs); this is the one place that turns them into Lua values. Used by event fields,
-- entity fields and java.wrap( ). Nothing (no kind) gives nil.
function I.from_java(kind, a, b, c, d, f, g, h, i)
	if kind == "v" then return a end
	if kind == "tree" then return tree(I.decode(a)) end
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
	if kind == "block_snapshot" then return I.new_block_snapshot(I.block_at(a, b, c, d), I.block_state_by_id(f)) end
	if kind == "profile" then return I.new_profile(a, b, c, d) end
	if kind == "effect" then return I.new_effect(a, b, c, d, f, g) end
	if kind == "played_sound" then return I.new_played_sound(a, b, c, d, f) end
	if kind == "server" then return lib.server end -- a command sent by the console or rcon
	if kind == "damage" then
		return I.new_damage(a, nested_entity(b, c), nested_entity(d, f), g and I.new_vec3(g, h, i) or nil)
	end
	return nil
end
