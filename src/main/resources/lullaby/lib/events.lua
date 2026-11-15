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
local J, lib, I = ...

local type, error, setmetatable, tostring, tonumber = type, error, setmetatable, tostring, tonumber
local sort, concat = table.sort, table.concat
local gmatch, match = string.gmatch, string.match

-- Java owns the events, their fields and the stages (EventBridge.java): read once here. The first line lists the
-- stages, then one line per event: "Name<TAB>field=number field=number".
local STAGES, STAGE_LIST = {}, {}
local EVENT_FIELDS, EVENT_NAMES = {}, {} -- event name -> { field name -> field number }; names in Java's order
do
	local first = true
	for line in gmatch(J.event_info(), "[^\n]+") do
		if first then
			for stage in gmatch(line, "%S+") do STAGES[stage] = true; STAGE_LIST[#STAGE_LIST + 1] = stage end
			first = false
		else
			local name, rest = match(line, "^([^\t]+)\t(.*)$")
			local fields = {}
			for field, number in gmatch(rest, "([%w_]+)=(%d+)") do fields[field] = tonumber(number) end
			EVENT_FIELDS[name] = fields
			EVENT_NAMES[#EVENT_NAMES + 1] = name
		end
	end
end

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

local function register(kind, a)
	if type(a) ~= "table" then error(kind .. " needs { }: " .. kind .. "{ \"EventName\", function(e) ... end }", 3) end
	local name, fn, order, stage = a[1], a[2], a.order, a.stage
	if type(name) ~= "string" then error(kind .. ": the first value must be the event name, got " .. I.type_name(name), 3) end
	if type(fn) ~= "function" then error(kind .. ": the second value must be a function, got " .. I.type_name(fn), 3) end
	if name ~= "reload" and not EVENT_FIELDS[name] then
		-- FORMAT: unknown event (the list comes from EventBridge.java)
		error(kind .. ": unknown event \"" .. name .. "\" (events so far: " .. concat(EVENT_NAMES, ", ") .. ", reload)", 3)
	end
	if order == nil then order = 0 end
	if type(order) ~= "number" then error(kind .. ": order must be a number, got " .. I.type_name(order), 3) end
	if kind == "after" then
		if stage ~= nil then error("after has no stage (it always runs once everything else is done)", 3) end
		stage = "monitor"
	else
		if stage == nil then stage = "normal" end
		if not STAGES[stage] then
			-- FORMAT: unknown stage (the list comes from Bukkit's priorities, through EventBridge.java)
			error("on: stage must be " .. quoted(STAGE_LIST) .. ", got " .. tostring(stage), 3)
		end
	end
	local key = name .. "|" .. kind .. "|" .. stage
	local list = handlers[key]
	if not list then
		list = {}
		handlers[key] = list
		if name ~= "reload" then
			listeners[J.listen(name, kind, stage)] = { list = list, fields = EVENT_FIELDS[name], name = name,
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
arg: 1 | text | The event name, like "PlayerJoinEvent" (all of them: event fields), or "reload".
arg: 2 | function | Gets the event e.
arg: order? | number | Lower runs first; ties by load order. Default 0.
arg: stage? | text | Place among other plugins: lowest, low, normal, high, highest. Default normal.
returns: nothing
note: Each handler runs as its own task and may wait; after its first wait the event is over and changes only give a warning. Keep values, not e: once every handler has ended without waiting, e can't be read any more.
status: unconfirmed
]]
function lib.on(a) register("on", a) end
--[[doc
name: after{ }
kind: event
group: Events
words: listen hook monitor
syntax: after{ "EventName", function(e) ... end, order = number }
lead: Runs the function once the event happened and was not cancelled; e is read only. All events: event fields.
arg: 1 | text | The event name, or "reload".
arg: 2 | function | Gets the event e.
arg: order? | number | Lower runs first; ties by load order. Default 0.
returns: nothing
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

-- Java answers a field read with a kind word plus values; turn that into a Lua value.
local function from_java(kind, a, b, c, d, f, g)
	if kind == "v" then return a end
	if kind == "player" then return I.player_by_uuid(a) end
	if kind == "block" then return I.block_at(a, b, c, d) end
	if kind == "location" then
		local world = I.world_by_id(a)
		return I.new_location(world, I.new_vec3(b, c, d), I.new_yaw_pitch(f, g))
	end
	if kind == "c" then return I.new_component(a, b) end
	return nil
end

local function ended(e, field)
	-- FORMAT: an event read or changed after its handlers ended
	error("the " .. live[e].name .. " is over: e." .. tostring(field) .. " can only be used while its handler runs "
		.. "(keep the values you need, not e)", 3)
end

local EVENT_META = {
	__index = function(e, field)
		local slot = slot_of[e]
		if not slot then ended(e, field) end
		local number = live[e].fields[field]
		if not number then return nil end
		return from_java(J.event_get(slot, number))
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
		local t = type(value)
		if t == "table" and (I.type_name(value) == "component" or I.debug_getmetatable(value) == nil) then
			J.event_set_component(slot, number, I.wire(value)) -- a text field set to a component table
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
		__index = fields,
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

--[[doc
id: events
name: event fields
kind: event
group: Events
words: fields e player cancelled join quit break message
lead: The e an event handler gets. Fields are writable in on{ }, read only in after{ }. PlayerJoinEvent: e.player, e.join_message. PlayerQuitEvent: e.player, e.quit_message, e.reason. BlockBreakEvent: e.player, e.block, e.exp_to_drop, e.drop_items, e.cancelled. PlayerMoveEvent: e.player, e.from, e.to (locations, read only for now), e.cancelled.
example:
  on{ "BlockBreakEvent", function(e)
    if e.player.name == "Notch" then e.cancelled = true end
  end }
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
status: unconfirmed
]]
