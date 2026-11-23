-- Lullaby library: what players see and hear: message{ }, particle{ }, sound{ }, stop_sound{ }, sound_seed( ), and
-- the played_sound value.
--   message{ player = p, text = "Hi" | component{ text = "Hi", color = "red" } | { text = "Hi", color = "red" } }   chat message (a string
--       is literal text, as /tellraw "Hi"; a table is a component) -> true, false if p is offline
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber, raw_next = type, error, setmetatable, tostring, tonumber, next
local debug_getmetatable = I.debug_getmetatable
local kind_of = I.kind_of
local slot_of = I.slot_of -- object -> its number in Java (Slots.java)

local PLAYED_SOUND = I.value_type("played_sound", { "sound", "source", "volume", "pitch", "seed" }, {})
-- FORMAT: a played_sound as text
PLAYED_SOUND.__tostring = function(s) return "played_sound " .. s[1] end

--[[doc
id: played_sound
name: played_sound
kind: type
group: Effects
words: sound audio noise volume pitch source
syntax: s.sound   s.source   s.volume   s.pitch   s.seed
lead: A sound the game is about to play, with how it plays, from events: event.locked_sound in BlockLockCheckEvent. The fields have the names sound{ } takes, so sound{ player = p, pos = p.pos, sound = s.sound, source = s.source, volume = s.volume, pitch = s.pitch } plays the same. A value: your own copy, it can't be changed.
field: sound | text | The sound's id: "minecraft:block.chest.locked".
field: source | text | Which volume slider plays it, vanilla's name as in /playsound: "master", "block", "player", "ambient", ...
field: volume | number | 1 is normal.
field: pitch | number | 1 is normal.
field: seed | number · nil | Which file of the sound plays; nil: a random one.
expert: Java object | none kept: a plain Lua value (Adventure's Sound)
expert: Stays valid | always (a copy)
expert: Compare | == compares all five fields
expert: Cost | made when the event field is read: 2–10 µs (guess, not measured)
expert: Can fail | a seed above 2^53 loses its last digits (Lua numbers); vanilla's own seeds come from sound_seed( ) and are exact
status: unconfirmed
]]
function I.new_played_sound(sound, source, volume, pitch, seed)
	return setmetatable({ sound, source, volume, pitch, seed }, PLAYED_SOUND)
end

-- ---------------------------------------------------------------------------------------------------------------
-- Actions
-- ---------------------------------------------------------------------------------------------------------------

local MESSAGE_TAKES = I.takes(0, "player", "text")
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
expert: Can fail | an unknown field (the error lists the ones it takes), player or text of the wrong kind; an offline player gives false, no error
expert: Reload | sent messages stay in chat
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.message(a)
	if type(a) ~= "table" then error("message{} needs { }: message{ player = p, text = \"...\" }", 2) end
	if kind_of[a.player] ~= "player" then error("message{}: player must be a player, got " .. I.type_name(a.player), 2) end
	local text = a.text
	local component = type(text) == "table" and (I.type_name(text) == "component" or debug_getmetatable(text) == nil)
	if not component and type(text) ~= "string" then
		error("message{}: text must be text or a component, got " .. I.type_name(text), 2)
	end
	I.only(a, "message{}", MESSAGE_TAKES, 2)
	if component then return J.message_component(slot_of[a.player], I.wire(text)) end
	return J.message(slot_of[a.player], text)
end

-- ---------------------------------------------------------------------------------------------------------------
-- Packet actions: straight on vanilla's packets, sent by nms/Packets.java (the one send core).
-- Every field crosses in ONE wire call; Java checks the fields and names the wrong one. Nothing is returned.
-- What each action takes is in its doc block below (the doc block is the one description; don't copy it here).
-- ---------------------------------------------------------------------------------------------------------------

-- The receivers as slot numbers: player = p or players = { p1, p2, ... } (exactly one of them). `what` is the
-- error prefix ("particle{}"). Returns the player's slot and nil, or nil and the list of slots. Errors use level 4:
-- receivers <- action_table <- the action <- the script.
local function receivers(a, what)
	local p, list = a.player, a.players
	-- FORMAT: player/players wrong
	if (p == nil) == (list == nil) then error(what .. ": give player = p or players = { ... } (one of them)", 4) end
	if p ~= nil then
		if kind_of[p] ~= "player" then error(what .. ": player must be a player, got " .. I.type_name(p), 4) end
		return slot_of[p], nil
	end
	if type(list) ~= "table" or debug_getmetatable(list) ~= nil then
		error(what .. ": players must be a list of players, got " .. I.type_name(list), 4)
	end
	local n = 0
	for key in raw_next, list do
		n = n + 1
		-- FORMAT: players is not a plain list
		if type(key) ~= "number" or key < 1 or key ~= math.floor(key) then
			error(what .. ": players must be a list (no other keys), found key " .. tostring(key), 4)
		end
	end
	local slots = {}
	for i = 1, n do
		local q = list[i]
		-- FORMAT: a list entry that is not a player (nil: a hole in the list)
		if kind_of[q] ~= "player" then error(what .. ": players[" .. i .. "] must be a player, got " .. I.type_name(q), 4) end
		slots[i] = slot_of[q]
	end
	return nil, slots
end
I.receivers = receivers -- clientside_summon{ } (entities.lua) takes its viewers the same way

-- The wire table of a packet action: ALL of a's fields (so Java's Fields.done can refuse a typo), with the
-- receivers as slots. name: "particle"; errors start with "particle{}:".
local function action_table(a, name)
	-- FORMAT: action called without { }
	if type(a) ~= "table" then error(name .. "{} needs { }: " .. name .. "{ player = p, ... }", 3) end
	local t = {}
	for key, value in raw_next, a do t[key] = value end
	t.player, t.players = receivers(a, name .. "{}")
	return t
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
expert: Can fail | an unknown field (the error lists the ones it takes), positions × count over particle_limit; any field vanilla's codec refuses (named in the error)
expert: Reload | particles already shown play out
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.particle(a)
	local t = action_table(a, "particle")
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
	J.particle(I.wire(t))
end

--[[doc
name: sound{ }
kind: doing
group: Effects
words: play audio noise
syntax: sound{ player = player | players = { player, ... }, sound = text | { sound_id = text, range = number }, source = text, pos = vec3, entity = entity | player | client_entity, volume = number, pitch = number, seed = number }
lead: Plays a sound to one player or a list of players.
arg: player / players | player · list of player | Who hears it. Exactly one of the two.
arg: sound | text · table | A sound id like "entity.player.levelup", or { sound_id, range }.
arg: source | text | Needed. Which volume slider plays it: one of vanilla's sound sources, as in /playsound ("master", "music", "ambient", ...). A wrong name gets an error that lists them all.
arg: pos / entity | vec3 · entity · player · client_entity | Needed, one of the two: where it plays; entity follows that entity or player (nothing plays if it is gone). A client_entity is heard only by players whose game shows it.
arg: volume? | number | Default 1.
arg: pitch? | number | Default 1.
arg: seed? | number | Picks which file of the sound plays. Random by default, as in vanilla. For one exact file use sound_seed( ).
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | 3–20 µs plus 0.5–2 µs per player (guess, not measured)
expert: Under the hood | one Lua → Java call; packet Sound or Sound Entity (play, clientbound)
expert: Can fail | an unknown field (the error lists the ones it takes), no source or no pos/entity, an unknown source (the error lists them), fields vanilla refuses
expert: Reload | sounds already playing keep playing
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.sound(a)
	local t = action_table(a, "sound")
	local entity = a.entity
	if entity ~= nil then
		-- FORMAT: sound entity is not an entity
		local kind = kind_of[entity]
		if kind ~= "player" and kind ~= "entity" and kind ~= "client_entity" then
			error("sound{}: entity must be an entity, a player or a client_entity, got " .. I.type_name(entity), 2)
		end
		t.entity = slot_of[entity]
	end
	J.sound(I.wire(t))
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
  sound{ player = player, sound = "ambient.basalt_deltas.additions", source = "ambient", entity = player, seed = seed }
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
expert: Can fail | an unknown field (the error lists the ones it takes), receivers of the wrong kind, an unknown source
expert: Reload | nothing to undo
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.stop_sound(a)
	J.stop_sound(I.wire(action_table(a, "stop_sound")))
end

