-- Lullaby library, file 4 of 7: events.
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

local type, error, setmetatable, tostring = type, error, setmetatable, tostring
local sort = table.sort

local STAGES = { lowest = true, low = true, normal = true, high = true, highest = true }
local handlers = {} -- "Name|kind|stage" -> handlers sorted by order, then registration
local count = 0

local function register(kind, a)
	if type(a) ~= "table" then error(kind .. " needs { }: " .. kind .. "{ \"EventName\", function(e) ... end }", 3) end
	local name, fn, order, stage = a[1], a[2], a.order, a.stage
	if type(name) ~= "string" then error(kind .. ": the first value must be the event name, got " .. I.type_name(name), 3) end
	if type(fn) ~= "function" then error(kind .. ": the second value must be a function, got " .. I.type_name(fn), 3) end
	if name ~= "reload" and not J.event_known(name) then
		error(kind .. ": unknown event \"" .. name .. "\" (events so far: PlayerJoinEvent, PlayerQuitEvent, "
			.. "BlockBreakEvent, reload)", 3)
	end
	if order == nil then order = 0 end
	if type(order) ~= "number" then error(kind .. ": order must be a number, got " .. I.type_name(order), 3) end
	if kind == "after" then
		if stage ~= nil then error("after has no stage (it always runs once everything else is done)", 3) end
		stage = "monitor"
	else
		if stage == nil then stage = "normal" end
		if not STAGES[stage] then
			error("on: stage must be \"lowest\", \"low\", \"normal\", \"high\" or \"highest\", got " .. tostring(stage), 3)
		end
	end
	local key = name .. "|" .. kind .. "|" .. stage
	local list = handlers[key]
	if not list then
		list = {}
		handlers[key] = list
		if name ~= "reload" then J.listen(name, kind, stage) end
	end
	count = count + 1
	list[#list + 1] = { fn = fn, order = order, seq = count }
	sort(list, function(x, y)
		if x.order ~= y.order then return x.order < y.order end
		return x.seq < y.seq
	end)
end

function lib.on(a) register("on", a) end
function lib.after(a) register("after", a) end

-- Event objects: e.field reads from the Java event, e.field = v writes to it (only in `on`, only before a wait).
local state = setmetatable({}, { __mode = "k" }) -- event object -> { java = its slot (Slots.java), name, kind, over }

-- Java answers a field read with a kind word plus values; turn that into a Lua value.
local function from_java(kind, a, b, c, d)
	if kind == "v" then return a end
	if kind == "player" then return I.player_by_uuid(a) end
	if kind == "block" then return I.block_at(a, b, c, d) end
	return nil
end

local EVENT_META = {
	__index = function(e, field)
		local s = state[e]
		return from_java(J.event_get(s.java, s.name, field))
	end,
	__newindex = function(e, field, value)
		local s = state[e]
		if s.kind == "after" then error("after events can't be changed (e." .. tostring(field) .. ")", 2) end
		if s.over then
			I.warn("the event is already over, changing e." .. tostring(field) .. " after a wait does nothing", 2)
			return
		end
		local t = type(value)
		if t ~= "boolean" and t ~= "number" and t ~= "string" and value ~= nil then
			error("e." .. tostring(field) .. " can't be set to a " .. I.type_name(value), 2)
		end
		J.event_set(s.java, s.name, field, value)
	end,
	__tostring = function(e) return state[e].name end,
	__metatable = false,
}

-- Called by Java for each event a script listens to.
-- java_event is the event's slot number; it is freed when the Lua event object is collected.
function I.dispatch(name, kind, stage, java_event)
	local list = handlers[name .. "|" .. kind .. "|" .. stage]
	if not list then
		J.free_slots(tostring(java_event))
		return
	end
	local e = setmetatable({}, EVENT_META)
	local s = { java = java_event, name = name, kind = kind, over = false }
	state[e] = s
	I.kind_of[e] = "event"
	I.give_slot(e, java_event)
	local origin = name .. " (" .. kind .. ")"
	for i = 1, #list do
		I.start_task(list[i].fn, { n = 1, e }, origin)
	end
	s.over = true
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
