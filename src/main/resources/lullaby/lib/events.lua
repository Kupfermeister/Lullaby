-- Lullaby library: events.
--
--   on{ "BlockBreakEvent", function(e) ... end, order = 0, stage = "normal" }   before it happens: may change e
--   after{ "PlayerJoinEvent", function(e) ... end, order = 0 }                  once it happened (not cancelled): read only
-- Lower order runs first, ties by load order (file order, then line order). stage places Lullaby among other plugins
-- (Bukkit priorities lowest..highest). Each handler runs as its own task, so it may wait; after its first wait the
-- event is over and changes only give a warning.
-- Java (EventBridge.java) registers one Bukkit listener per event, kind and stage that scripts use, and calls
-- I.dispatch, which runs the handlers here in order. Event fields are read and written through Java on each access.
-- "reload" is Lullaby's own event: it runs after a /lullaby reload has loaded every script (and refreshed every
-- online player's commands). Its e is read only: e.scripts (files loaded), e.errors, e.ms (how long it took),
-- e.cause ("command": /lullaby reload) and e.player (who reloaded; nil from the console).
-- "input" is Lullaby's own too: one event per input packet a player's game sends (nms/Input.java owns the packets
-- and their fields; see the input doc block at the end of this file).
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber = type, error, setmetatable, tostring, tonumber
local sort, concat = table.sort, table.concat
local gmatch = string.gmatch
local debug_getinfo, pairs, pcall, rawget, rawequal = debug.getinfo, pairs, pcall, rawget, rawequal

-- Java owns the events, their fields and the stages (EventBridge.java, from the generated list events.txt). The
-- stages are read once here; an event's fields ("name=number ...") the first time a script uses that event, so the
-- ~400 events nobody uses cost nothing.
local STAGES, STAGE_LIST = {}, {}
for stage in gmatch(J.event_info(), "%S+") do STAGES[stage] = true; STAGE_LIST[#STAGE_LIST + 1] = stage end
local EVENT_FIELDS = {} -- event name -> { field name -> field number }, filled per event on first use
local EVENT_LISTS = {}  -- event name -> { field name -> true } for its list fields
local OWN = { reload = true, input = true } -- Lullaby's own events (not Paper's: no fields from EventBridge)

-- '"a", "b" or "c"'
local function quoted(list)
	local q = {}
	for i = 1, #list do q[i] = '"' .. list[i] .. '"' end
	if #q <= 1 then return concat(q) end
	return concat(q, ", ", 1, #q - 1) .. " or " .. q[#q]
end

local handlers = {}  -- "Name|kind|stage" -> handlers sorted by order, then registration
local listeners = {} -- listener number (from J.listen) -> { list = handlers, fields, name, kind, origin }
local count = 0

local REGISTER_TAKES = I.takes(2, "order", "stage")
local function register(kind, a)
	if type(a) ~= "table" then error(kind .. "{} needs { }: " .. kind .. "{ \"EventName\", function(e) ... end }", 3) end
	local name, fn, order, stage = a[1], a[2], a.order, a.stage
	local what = kind .. "{}"
	if type(name) ~= "string" then error(what .. ": the first value must be the event name, got " .. I.type_name(name), 3) end
	if type(fn) ~= "function" then error(what .. ": the second value must be a function, got " .. I.type_name(fn), 3) end
	-- FORMAT: a stage given for Lullaby's own input event
	if name == "input" and stage ~= nil then error(what .. ": input has no stage (it is Lullaby's own event, other "
		.. "plugins don't see it)", 3) end
	if not OWN[name] and not EVENT_FIELDS[name] then
		-- why (unknown, with close names; or off the main thread) is worded in EventBridge.java
		local info, why = J.event_fields(name)
		if not info then error(what .. ": " .. why, 3) end
		local fields, lists = {}, {}
		for field, number, list in gmatch(info, "([%w_]+)=(%d+)(:?%a*)") do
			fields[field] = tonumber(number)
			if list == ":list" then lists[field] = true end
		end
		EVENT_FIELDS[name] = fields
		EVENT_LISTS[name] = lists
	end
	if order == nil then order = 0 end
	if type(order) ~= "number" then error(what .. ": order must be a number, got " .. I.type_name(order), 3) end
	if kind == "after" then
		if stage ~= nil then error("after{} has no stage (it always runs once everything else is done)", 3) end
		stage = "monitor"
	else
		if stage == nil then stage = "normal" end
		if not STAGES[stage] then
			-- FORMAT: unknown stage (the list comes from Bukkit's priorities, through EventBridge.java)
			error("on{}: stage must be " .. quoted(STAGE_LIST) .. ", got " .. tostring(stage), 3)
		end
	end
	I.only(a, what, REGISTER_TAKES, 3)
	local key = name .. "|" .. kind .. "|" .. stage
	local list = handlers[key]
	if not list then
		list = {}
		handlers[key] = list
		if name == "input" then
			J.input_listen(kind == "after")
		elseif name ~= "reload" then
			listeners[J.listen(name, kind, stage)] = { list = list, fields = EVENT_FIELDS[name],
				lists = EVENT_LISTS[name], name = name,
				kind = kind, origin = name .. " (" .. kind .. ")" }
		end
	end
	count = count + 1
	list[#list + 1] = { fn = fn, order = order, seq = count }
	sort(list, function(x, y)
		if x.order ~= y.order then return x.order < y.order end
		return x.seq < y.seq
	end)
end

--[[doc
name: on{ }
kind: event
group: Events
words: listen hook before cancel
syntax: on{ "EventName", function(e) ... end, order = number, stage = text }
lead: Runs the function before the event happens; it may change e (e.cancelled = true, ...).
arg: 1 | text | The event name, like "PlayerJoinEvent" (every Paper event: the Events list), or Lullaby's own "reload" or "input".
arg: 2 | function | Gets the event e.
arg: order? | number | Lower runs first; ties by load order. Default 0.
arg: stage? | text | Place among other plugins: lowest, low, normal, high, highest. Default normal.
returns: nothing
note: Each handler runs as its own task and may wait; after its first wait the event is over and changes only give a warning. Keep values, not e: once every handler has ended without waiting, e can't be read any more.
expert: Thread | main thread, like every script
expert: Cost | unused events cost zero; one event with a handler reading two fields: goal ~10 µs, guess 5–30 µs (not measured); registering: 5–50 µs once (guess, not measured)
expert: Under the hood | EventBridge.java: the event's class and methods are looked up the first time a script uses it; one Bukkit listener per event, kind and stage, made only when a script uses it; fields go by number, never by name; the event is freed at once when no handler waited; an event that comes off the main thread is skipped (said once in the console)
expert: Can fail | an unknown field (the error lists the ones it takes), at load: unknown event (the error names close ones), an event Paper fires off the main thread, bad order or stage; while running: an unknown field written, a field Lullaby can't read or set yet, a wrong value type, e used after its handlers ended
expert: Reload | every listener is removed; scripts register again while loading
expert: Time limit | each handler is its own task with its own clock, except for an event a script's own call fires (teleport{ } teleporting, a Java door call): that handler runs inside the call and shares the caller's clock; checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.on(a) register("on", a) end
--[[doc
name: after{ }
kind: event
group: Events
words: listen hook monitor
syntax: after{ "EventName", function(e) ... end, order = number }
lead: Runs the function once the event happened and was not cancelled; e is read only. Every Paper event: the Events list.
arg: 1 | text | The event name, or "reload" or "input".
arg: 2 | function | Gets the event e.
arg: order? | number | Lower runs first; ties by load order. Default 0.
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | as on{ }: goal ~10 µs per event with a handler reading two fields, guess 5–30 µs (not measured)
expert: Under the hood | a Bukkit listener at MONITOR priority that skips cancelled events; e is read only
expert: Can fail | an unknown field (the error lists the ones it takes), at load: unknown event, a stage given; while running: any write to e
expert: Reload | every listener is removed; scripts register again while loading
expert: Time limit | each handler is its own task; checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.after(a) register("after", a) end

-- Event objects: e.field reads from the Java event, e.field = v writes to it (only in `on`, only before a wait).
-- Busy events (movement) can fire many times per tick, so one event costs one small table and nothing else: no
-- names cross to Java (fields go by number), and if no handler waited, the Java event is freed at once instead of by
-- the garbage collector. Such an event can't be read afterwards (a script that kept e gets a clear error).
local live = setmetatable({}, { __mode = "k" }) -- event object -> its listener record
local over = setmetatable({}, { __mode = "k" }) -- event object -> true once a handler waited past it
local slot_of = I.slot_of


-- "cancelled, player, ..." for the error on a field the event doesn't have; made once per event.
local function names_of(l)
	if not l.names then
		local list = {}
		for name in pairs(l.fields) do list[#list + 1] = name end
		sort(list)
		l.names = #list > 0 and concat(list, ", ") or "no fields"
	end
	return l.names
end

local function ended(e, field)
	-- FORMAT: an event read or changed after its handlers ended
	error("the " .. live[e].name .. " is over: e." .. tostring(field) .. " can only be used while its handler runs "
		.. "(keep the values you need, not e)", 3)
end

-- List fields and block snapshots: "a change goes where the value lives". Reading one gives a plain Lua list (or a
-- block_snapshot value), the SAME one for every read while the handler runs, so table.remove(e.drops, 1) and
-- e.drops[1].count = 2 work. When the handler ends or first waits (flush, from I.dispatch), a changed one goes back
-- into the Paper event; where it can't (after{ }, a field Paper doesn't let change) that is a red error, so a change
-- is never dropped silently. Unchanged ones cost nothing more. Java side: EventBridge.setList / setSnapshot.
local held = setmetatable({}, { __mode = "k" }) -- event object -> { field -> { value, number, line, snap } }

-- "scripts/x.lua:12": the script line running now (the first script frame; tail calls skip library frames).
local function script_line()
	for level = 2, 20 do
		local info = debug_getinfo(level, "Sl")
		if not info then break end
		if info.source:sub(1, 9) == "@scripts/" then return info.source:sub(2) .. ":" .. info.currentline end
	end
	return "?"
end

local function snap_of(value)
	if I.type_name(value) == "block_snapshot" then return rawget(value, 2) end
	local snap = { n = #value }
	for i = 1, snap.n do
		local v = value[i]
		snap[i] = v
		if I.type_name(v) == "block_snapshot" then snap[-i] = rawget(v, 2) end
	end
	return snap
end

local function changed(value, snap)
	if I.type_name(value) == "block_snapshot" then return rawget(value, 2) ~= snap end
	if #value ~= snap.n then return true end
	for i = 1, snap.n do
		local v = value[i]
		if not rawequal(v, snap[i]) then return true end
		local kind = I.type_name(v)
		if kind == "item_stack" and I.item_changed(v) then return true end
		if kind == "block_snapshot" and rawget(v, 2) ~= snap[-i] then return true end
	end
	return false
end

-- A list entry as the wire sends it to EventBridge.setList; item_stack helpers go into `helpers` (freed after).
local function entry(v, helpers)
	local kind = I.type_name(v)
	if kind == "item_stack" then
		local slot, helper = I.item_java(v)
		if helper then helpers[#helpers + 1] = helper end
		return { "item_stack", slot }
	end
	if kind == "entity" or kind == "player" then return { "entity", slot_of[v] } end
	if kind == "block" then
		local pos = v.pos
		return { "block", slot_of[v.world], pos[1], pos[2], pos[3] }
	end
	if kind == "block_snapshot" then
		local b = rawget(v, 1)
		local pos = b.pos
		return { "block_snapshot", slot_of[b.world], pos[1], pos[2], pos[3], rawget(v, 2)[1] }
	end
	if kind == "component" or (kind == "table" and I.debug_getmetatable(v) == nil) then return { "component", v } end
	if kind == "string" or kind == "number" or kind == "boolean" then return v end
	return { "?", kind }
end

local function apply(slot, number, value)
	if I.type_name(value) == "block_snapshot" then
		local b = rawget(value, 1)
		local pos = b.pos
		J.event_set_snapshot(slot, number, slot_of[b.world], pos[1], pos[2], pos[3], rawget(value, 2)[1])
		return
	end
	local list, helpers = {}, {}
	local ok, err = pcall(function()
		for i = 1, #value do list[i] = entry(value[i], helpers) end
		J.event_set_list(slot, number, I.wire(list))
	end)
	for i = 1, #helpers do I.release(helpers[i]) end
	if not ok then error(err, 0) end
end

-- A coroutine that never ran: reporting "from" it finds no script frames, so the report uses the line in the message
-- (the line that read the list) even when a script fired the event itself (java door, an action that fires one).
local NO_FRAMES = coroutine.create(function() end)

-- After each handler of e: puts changed lists and snapshots back, or reports why it can't (at the line that read it).
local function flush(e, l)
	local h = held[e]
	held[e] = nil
	local slot = slot_of[e]
	for field, hold in pairs(h) do
		if changed(hold.value, hold.snap) then
			local ok, err = true, nil
			-- FORMAT: a list or snapshot changed in after{ }
			if l.kind == "after" then ok, err = false, "after events can't be changed (e." .. field .. " was changed)"
			else ok, err = pcall(apply, slot, hold.number, hold.value) end
			if not ok then
				I.report("error", NO_FRAMES, 0, hold.line .. ": " .. tostring(err):gsub("^[^\n]-:%d+: ", "", 1), l.origin)
			end
		end
	end
end

-- The list or snapshot read from Java, kept for this handler (see held above).
local function hold(e, field, number, kind, ...)
	local value
	if kind == "list" then
		local n = ...
		value = {}
		for i = 1, n do value[i] = I.from_java(J.event_list_item(i)) end
	else
		value = I.from_java(kind, ...)
	end
	local h = held[e]
	if not h then h = {}; held[e] = h end
	h[field] = { value = value, number = number, snap = snap_of(value), line = script_line() }
	return value
end

local function read(e, field, number, kind, ...)
	if kind == "list" or kind == "block_snapshot" then return hold(e, field, number, kind, ...) end
	return I.from_java(kind, ...)
end

local EVENT_META = {
	__index = function(e, field)
		local slot = slot_of[e]
		if not slot then ended(e, field) end
		local h = held[e]
		if h and h[field] then return h[field].value end
		local l = live[e]
		local number = l.fields[field]
		if not number then I.no_field(l.name, field, names_of(l), 2) end
		return read(e, field, number, J.event_get(slot, number))
	end,
	__newindex = function(e, field, value)
		local l, slot = live[e], slot_of[e]
		if not slot then ended(e, field) end
		if l.kind == "after" then error("after events can't be changed (e." .. tostring(field) .. ")", 2) end
		if over[e] then
			I.warn("the event is already over, changing e." .. tostring(field) .. " after a wait does nothing", 2)
			return
		end
		local number = l.fields[field]
		-- FORMAT: a field the event doesn't have
		if not number then error(l.name .. " has no field e." .. tostring(field), 2) end
		local t, kind = type(value), I.type_name(value)
		if l.lists[field] then
			-- FORMAT: a list field set to something that isn't a list
			if kind ~= "table" then error("e." .. tostring(field) .. " must be a list, got " .. kind, 2) end
			apply(slot, number, value)
			-- the new list is the one this handler now holds: later changes to it go back too
			local h = held[e]
			if not h then h = {}; held[e] = h end
			h[field] = { value = value, number = number, snap = snap_of(value), line = script_line() }
			return
		end
		if t == "table" and (kind == "component" or I.debug_getmetatable(value) == nil) then
			J.event_set_component(slot, number, I.wire(value)) -- a text field set to a component table
			return
		end
		if kind == "item_stack" then
			local item, helper = I.item_java(value)
			local ok, err = pcall(J.event_set_item, slot, number, item)
			if helper then I.release(helper) end
			if not ok then error(err, 0) end
			return
		end
		if kind == "location" then
			local pos, rot = value[2], value[3]
			J.event_set_location(slot, number, slot_of[value[1]], pos[1], pos[2], pos[3], rot[1], rot[2])
			return
		end
		if kind == "vec3" then
			J.event_set_vec3(slot, number, value[1], value[2], value[3])
			return
		end
		if kind == "entity" or kind == "player" then
			J.event_set_entity(slot, number, slot_of[value])
			return
		end
		if kind == "block_state" then
			J.event_set_block_state(slot, number, value[1])
			return
		end
		if t ~= "boolean" and t ~= "number" and t ~= "string" and value ~= nil then
			error("e." .. tostring(field) .. " can't be set to a " .. I.type_name(value), 2)
		end
		J.event_set(slot, number, value)
	end,
	__tostring = function(e) return live[e].name end,
	__metatable = false,
}
I.kind_by_meta[EVENT_META] = "event"

-- Called by Java for each event a script listens to: the listener's number and the event's slot number.
function I.dispatch(listener, slot)
	local l = listeners[listener]
	local e = setmetatable({}, EVENT_META)
	live[e] = l
	slot_of[e] = slot
	local list, waited = l.list, false
	for i = 1, #list do
		if I.start_handler(list[i].fn, e, l.origin) then waited = true end
		if held[e] then flush(e, l) end
	end
	if waited then
		over[e] = true
		I.give_slot(e, slot) -- a waiting handler still holds e: freed when e is collected
	else
		slot_of[e] = nil
		I.free_slot(slot)
	end
end

-- Called by Java after a /lullaby reload has loaded every script.
function I.fire_reload(scripts, errors, ms, uuid)
	local fields = { scripts = scripts, errors = errors, ms = ms, cause = "command",
		player = uuid and I.player_by_uuid(uuid) or nil }
	local e = setmetatable({}, {
		__index = function(_, field)
			local value = fields[field]
			if value == nil and field ~= "player" then I.no_field("reload", field, "scripts, errors, ms, cause, player", 2) end
			return value
		end,
		__newindex = function(_, field) error("the reload event can't be changed (e." .. tostring(field) .. ")", 2) end,
		__tostring = function() return "reload" end,
		__metatable = false,
	})
	I.kind_of[e] = "event"
	for _, kind in ipairs({ "on", "after" }) do
		local list = handlers["reload|" .. kind .. "|" .. (kind == "on" and "normal" or "monitor")]
		if list then
			for i = 1, #list do I.start_task(list[i].fn, { n = 1, e }, "reload (" .. kind .. ")") end
		end
	end
end

-- The input event (Lullaby's own, see nms/Input.java). Java owns the packets and their fields: the key names come
-- first (the bits of pressed and released), then "name field=number ..." per packet, in Java's order.
local info_lines = gmatch(J.input_info(), "[^\n]+")
local KEY_NAMES = {}
for key in gmatch(info_lines(), "%S+") do KEY_NAMES[#KEY_NAMES + 1] = key end
local KEY_LIST = concat(KEY_NAMES, ", ")
local PACKETS = {} -- packet number (from 0, as Java counts) -> { name, fields = { name -> number }, names (for errors) }
local packet_count = 0
for line in info_lines do
	local name, rest = line:match("^(%S+)%s*(.*)$")
	local fields, names = {}, { "cancelled", "packet", "player" }
	for field, number in gmatch(rest, "([%w_]+)=(%d+)") do
		fields[field] = tonumber(number)
		names[#names + 1] = field
	end
	sort(names)
	-- not #PACKETS: Lua's length ignores index 0, so it would stay 0 and every packet would land on PACKETS[0]
	PACKETS[packet_count] = { name = name, fields = fields, names = concat(names, ", ") }
	packet_count = packet_count + 1
end

-- e.pressed / e.released: one read-only table per combination of keys (made the first time it is needed), so reading
-- them makes no new table per event. A key name it doesn't have is an error, as for any field.
local keysets = {}
local function keyset(mask)
	local set = keysets[mask]
	if set then return set end
	local values = {}
	for i = 1, #KEY_NAMES do values[KEY_NAMES[i]] = mask % 2 ^ i >= 2 ^ (i - 1) end
	set = setmetatable({}, {
		__index = function(_, key)
			local v = values[key]
			if v == nil then I.no_field("the keys", key, KEY_LIST, 2) end
			return v
		end,
		-- FORMAT: e.pressed or e.released changed
		__newindex = function(_, key) error("the keys can't be changed (" .. tostring(key) .. ")", 2) end,
		__metatable = false,
	})
	keysets[mask] = set
	return set
end

-- One input event costs one table (e) plus entries in these weak tables; the packet's fields are read from Java on
-- each access, by number. Like other events, e can't be read once every handler has ended without waiting.
local input_packet = setmetatable({}, { __mode = "k" })    -- e -> its PACKETS entry
local input_player = setmetatable({}, { __mode = "k" })    -- e -> the player's UUID
local input_refusal = setmetatable({}, { __mode = "k" })   -- e -> why it can't be cancelled (absent: it can)
local input_cancelled = setmetatable({}, { __mode = "k" }) -- e -> true once an on handler cancelled it
local input_after = setmetatable({}, { __mode = "k" })     -- e -> true in after{ } (read only)

local function input_ended(e, field)
	-- FORMAT: an event read or changed after its handlers ended
	error("the input event is over: e." .. tostring(field) .. " can only be used while its handler runs (keep the "
		.. "values you need, not e)", 3)
end

local INPUT_META = {
	__index = function(e, field)
		local slot = slot_of[e]
		if not slot then input_ended(e, field) end
		local p = input_packet[e]
		if field == "packet" then return p.name end
		if field == "player" then return I.player_by_uuid(input_player[e]) end
		if field == "cancelled" then return input_cancelled[e] == true end
		local number = p.fields[field]
		if not number then I.no_field(p.name, field, p.names, 2) end
		if field == "pressed" or field == "released" then
			local _, mask = J.input_get(slot, number)
			return keyset(mask)
		end
		return I.from_java(J.input_get(slot, number))
	end,
	__newindex = function(e, field, value)
		if not slot_of[e] then input_ended(e, field) end
		local p = input_packet[e]
		if field ~= "cancelled" then
			-- FORMAT: a field the event doesn't have; an input field written
			if not p.fields[field] and field ~= "packet" and field ~= "player" then
				error(p.name .. " has no field e." .. tostring(field), 2)
			end
			error("e." .. tostring(field) .. " can't be changed (it is what the player's game sent)", 2)
		end
		if input_after[e] then error("after events can't be changed (e.cancelled)", 2) end
		if over[e] then
			I.warn("the event is already over, changing e.cancelled after a wait does nothing", 2)
			return
		end
		-- FORMAT: e.cancelled set to something that isn't true or false
		if type(value) ~= "boolean" then error("e.cancelled must be true or false, got " .. I.type_name(value), 2) end
		local refusal = input_refusal[e]
		if value and refusal then error(refusal, 2) end
		input_cancelled[e] = value or nil
	end,
	__tostring = function() return "input" end,
	__metatable = false,
}
I.kind_by_meta[INPUT_META] = "event"

-- Called by Java for each input packet while a script listens (on handlers, then after handlers once vanilla handled
-- the packet). Returns true if an on handler cancelled it.
function I.dispatch_input(after, packet, slot, uuid, refusal)
	local list = handlers[after and "input|after|monitor" or "input|on|normal"]
	if not list then
		I.free_slot(slot)
		return false
	end
	local e = setmetatable({}, INPUT_META)
	slot_of[e] = slot
	input_packet[e] = PACKETS[packet]
	input_player[e] = uuid
	input_refusal[e] = refusal
	input_after[e] = after or nil
	local waited = false
	local origin = after and "input (after)" or "input (on)"
	for i = 1, #list do
		if I.start_handler(list[i].fn, e, origin) then waited = true end
	end
	local cancelled = input_cancelled[e] == true
	if waited then
		over[e] = true
		I.give_slot(e, slot) -- a waiting handler still holds e: freed when e is collected
	else
		slot_of[e] = nil
		I.free_slot(slot)
	end
	return cancelled
end

--[[doc
id: events
name: Event fields
kind: event
group: Events
words: fields e player cancelled
lead: The e an event handler gets. Its fields are the event's Paper getters by one rule: getJoinMessage() is e.join_message, isCancelled() is e.cancelled. Each event's page lists its fields, what they hold and whether they can be changed; fields are writable in on{ }, read only in after{ }. A name the event doesn't have is an error that lists its fields, never nil. A list field (e.drops) is a plain Lua list and a block snapshot (e.new_state) a block_snapshot, the same one for every read in the handler: change it (table.remove(e.drops, 1), e.drops[1].count = 2, e.new_state.state = ...) and the change goes back into the event when the handler ends or first waits; where it can't (after{ }, a list Paper gives read only) that is a red error, never a silent loss. Fields Lullaby can't hand to scripts yet (inventories, ...) give a red error too. Enum fields are lowercase words ("right_click_block"), keys are ids ("minecraft:stone").
example:
  on{ "BlockBreakEvent", function(e)
    if e.player.name == "Notch" then e.cancelled = true end
  end }
expert: Thread | main thread, like every script
expert: Cost | one field read or write: 0.5–3 µs, one Java call (guess, not measured); a player or block field reuses its object; an item_stack field is copied each read: 10–50 µs (guess, not measured)
expert: Under the hood | fields by number (from events.txt, which tools/events/EventList.java generates from Paper's classes), read from the live Paper event on each access, never copied (items are)
expert: Can fail | a field the event doesn't have (read or written); a field Lullaby can't read or set yet; a value the field can't take; any use after the handlers ended; a changed list or snapshot that can't go back (the error names the line that read it)
expert: Reload | events are never kept across a reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
--[[doc
id: reload
name: reload
kind: event
group: Events
words: reload restart loaded scripts
syntax: after{ "reload", function(e) ... end }
lead: Lullaby's own event: runs after /lullaby reload has loaded every script. e is read only: e.scripts (files loaded), e.errors, e.ms (how long it took), e.cause ("command") and e.player (who reloaded; nil from the console).
expert: Thread | main thread, like every script
expert: Cost | runs once per reload: as its handlers (guess, not measured)
expert: Under the hood | Lullaby's own: fired after every script loaded and online players' commands were refreshed; e is a plain read-only table
expert: Can fail | any write to e
expert: Reload | it is the reload
expert: Time limit | each handler is its own task; checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
--[[doc
id: input
name: input
kind: event
group: Events
words: keys keyboard mouse click press jump space sneak shift sprint punch swing attack hit right left use place break dig drop swap hotbar scroll middle pick fly elytra spear stab packet
syntax: on{ "input", function(e) ... end, order = number }
lead: Lullaby's own event for what a player's game sends when they press a key or click: one event per vanilla input packet, right before vanilla acts on it. e.packet says which packet; each packet has its own fields (Mojang's names), listed in the tree below with when it comes and how to tell it apart. on{ } may cancel it: vanilla never sees the packet, and the player's game gets what vanilla sends when it refuses that input itself (the block or the hotbar is shown again as it is). after{ } runs once vanilla has handled it (the block is placed, the item dropped); e is read only there.
field: packet | text | Which packet: a name from the tree below.
field: player | player | Who sent it.
field: cancelled | boolean | true cancels the packet (on{ } only, before a wait). The tree says what can't be cancelled; trying is a red error.
note: This is the raw input, before vanilla checks it: reach, game mode, cooldowns. A cheating client can send anything (an attack across the map), so for rules that must hold use the checked Paper events (BlockBreakEvent, PrePlayerAttackEntityEvent, ...). Some input never reaches the server, so no event sees it: E (own inventory), T, Tab, F3, Esc, and a right click on air with an empty hand. Reading a field another packet has is a red error (e.pressed on a punch): check e.packet first; e.packet == "player_input" and e.pressed.jump is safe, because Lua stops at the first false.
example:
  on{ "input", function(e)
    -- double jump: space pressed in the air
    if e.packet == "player_input" and e.pressed.jump and not e.player.on_ground then
      velocity{ entity = e.player, velocity = vec3(0, 0.8, 0) }
    end
  end }
tree-from: src/main/java/io/github/kupfermeister/lullaby/nms/Input.java Kind
tree: player_input | | A movement key went down or up: W A S D, space, shift, sprint. Sent only when one of them changed, with all seven as they are now: tapping space gives two (down, then up). Can't be cancelled.
tree: player_input.forward | boolean | W is down now.
tree: player_input.backward | boolean | S is down now.
tree: player_input.left | boolean | A is down now.
tree: player_input.right | boolean | D is down now.
tree: player_input.jump | boolean | Space is down now. The key, not a jump: also in water, flying or riding.
tree: player_input.shift | boolean | Shift (sneak) is down now.
tree: player_input.sprint | boolean | The sprint key is down now; whether they really sprint is player_command start_sprinting.
tree: player_input.pressed | keys | The keys that went down with this packet, by the same seven names: e.pressed.jump is true only at the moment space goes down. Lullaby's own: it compares with the player's keys before.
tree: player_input.released | keys | The keys that went up with this packet: e.released.shift when they stop sneaking.
tree: punch | | A left click: the arm swing, on air, a block or an entity alike. Hitting an entity also sends attack; holding the button on a block sends punch again and again while it breaks (the breaking itself is player_action). Cancelled: no swing.
tree: attack | | A left click on an entity: the hit. Not with a spear held (that is player_action stab). Cancelled: no hit.
tree: attack.entity | entity · player · nil | Who was hit; nil if the game knows no such entity (gone meanwhile, or a cheating client).
tree: interact | | A right click on an entity: trade, ride, shear, name tag, ... The player's game tries main_hand first and off_hand only if the main hand did nothing, so one click can send two (check e.hand). Cancelled: nothing happens, the player's inventory and the entity are shown again as they are.
tree: interact.entity | entity · player · nil | What was clicked; nil if the game knows no such entity.
tree: interact.hand | text | "main_hand" or "off_hand".
tree: interact.location | vec3 | Where on the entity the click hit, measured from the entity's position (its feet).
tree: interact.using_secondary_action | boolean | They sneak while clicking (vanilla's "secondary action").
tree: use_item | | A right click that uses the held item itself: eat, drink, draw a bow, throw, raise a shield, ... Comes after use_item_on when clicking the block did nothing, and once per hand tried (main_hand first). Never with an empty hand. Holding the button sends it once; letting go is player_action release_use_item. Cancelled: the item isn't used.
tree: use_item.hand | text | "main_hand" or "off_hand".
tree: use_item.y_rot | number | Where they looked, sideways (vanilla's yRot, in degrees).
tree: use_item.x_rot | number | Up and down (vanilla's xRot, in degrees: -90 is straight up, 90 straight down).
tree: use_item_on | | A right click on a block: open a door or chest, place a block, use bone meal, ... Once per hand tried (main_hand first). Cancelled: nothing happens, the block and its neighbour on the clicked side are shown again as they are.
tree: use_item_on.hand | text | "main_hand" or "off_hand".
tree: use_item_on.block | block | The block clicked.
tree: use_item_on.direction | text | The side clicked: "down", "up", "north", "south", "west" or "east".
tree: use_item_on.location | vec3 | The exact point clicked, in world positions.
tree: use_item_on.inside | boolean | The click started inside the block (vanilla's "inside": the player's eyes are in it).
tree: use_item_on.world_border_hit | boolean | The click hit the world border.
tree: player_action | | Breaking, dropping, swapping and letting go; e.action says which.
tree: player_action.action | text | What they did:
tree: player_action.action.start_destroy_block | | Left button held on a block: breaking starts (in creative the block breaks at once). Cancelled: no breaking, the block is shown again.
tree: player_action.action.change_destroy_direction | | Still breaking the same block, now looking at another side of it.
tree: player_action.action.abort_destroy_block | | Let go or looked away before the block broke.
tree: player_action.action.stop_destroy_block | | Breaking finished: their game says the block is broken (vanilla checks the time it took). Cancelled: the block stays.
tree: player_action.action.drop_all_items | | Ctrl+Q: the whole stack in hand. Cancelled: it stays in the hand.
tree: player_action.action.drop_item | | Q: one item from the hand (dropping from an open inventory is a container click, not this). Cancelled: it stays.
tree: player_action.action.release_use_item | | Let go of right click while using an item: the bow shoots, eating stops. Can't be cancelled.
tree: player_action.action.swap_item_with_offhand | | F: swap the hands. Cancelled: nothing moves.
tree: player_action.action.stab | | A left click with a spear (an item with the piercing_weapon component): the spear's hit. Vanilla ignores attack while one is held. Cancelled: no hit.
tree: player_action.block | block | The block, for the destroy actions; for the others vanilla sends the block at 0 0 0.
tree: player_action.direction | text | The side of the block, for the destroy actions; "down" for the others.
tree: player_command | | A state their game switched; e.action says which.
tree: player_command.action | text | What switched:
tree: player_command.action.stop_sleeping | | "Leave Bed" clicked. Cancelled: they stay in bed.
tree: player_command.action.start_sprinting | | Sprinting starts (sprint key, double-tap W). Can't be cancelled.
tree: player_command.action.stop_sprinting | | Sprinting stops. Can't be cancelled.
tree: player_command.action.start_riding_jump | | A jump on a mount that jumps (horse, camel, ...); e.data is its strength. Cancelled: no jump.
tree: player_command.action.stop_riding_jump | | The mount's jump ended.
tree: player_command.action.open_inventory | | E while riding a mount with an inventory (horse, llama, ...): its inventory, not their own. Cancelled: nothing opens.
tree: player_command.action.start_fall_flying | | Space in the air with an elytra: gliding starts. Cancelled: they stop gliding (vanilla's own refusal).
tree: player_command.data | number | The jump's strength for start_riding_jump (vanilla's number); 0 for the others.
tree: set_carried_item | | A hotbar slot chosen: 1–9 or the mouse wheel. Cancelled: the old slot stays selected.
tree: set_carried_item.slot | number | The new slot: 0 (left) to 8 (right), vanilla's count.
tree: pick_item_from_block | | Middle click on a block. Cancelled: nothing is picked.
tree: pick_item_from_block.block | block | The block.
tree: pick_item_from_block.include_data | boolean | Ctrl was held too (creative: copy the block with its contents).
tree: pick_item_from_entity | | Middle click on an entity. Cancelled: nothing is picked.
tree: pick_item_from_entity.entity | entity · player · nil | The entity; nil if the game knows no such entity.
tree: pick_item_from_entity.include_data | boolean | Ctrl was held too.
tree: player_abilities | | Flying switched on or off: double-tap space where they may fly (creative, spectator, allowed flight). Cancelled: they keep their flying state.
tree: player_abilities.flying | boolean | true: flying starts; false: it stops.
expert: Thread | main thread, like every script; the packets arrive on the network threads and wait in vanilla's own queue, in the order they came
expert: Cost | no handler: nothing in the network at all; with a handler: one event per input packet (a few per second per player), guess 10–40 µs each (not measured)
expert: Under the hood | nms/Input.java: a hook in each player's network pipeline (only while a script listens) wraps the input packets; vanilla queues them for the main thread as always, where the on handlers run, then vanilla (unless cancelled), then the after handlers; fields are read from the packet by number. Paper's packet limiter counts them for its all-packets limit, but a limit set for one packet in paper-global.yml (packet-limiter overrides) doesn't see them while a script listens
expert: Can fail | at load: a stage given; while running: a field the packet doesn't have (the error lists the ones it has), a field written (only cancelled can be), cancelling what can't be cancelled, e used after its handlers ended
expert: Reload | the hook leaves every pipeline; scripts listen again while loading. Packets already waiting go to vanilla unchanged
expert: Time limit | each handler is its own task; checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
