-- Lullaby library: codec values and the wire.
--
-- THE WIRE: tables cross to Java as bytes in LuaJIT's own serialization format (string.buffer encode/decode, done in
-- C), read and written by Wire.java. Small values (block_pos, vec3, ...), objects and text components inside a table
-- are tagged by their metatable (the KINDS list, read from nms/Codecs.java, its owner), so Java knows what they are
-- without a Lua loop. Java turns the tables into vanilla codec input (LuaOps.java).
--
-- LAZY VALUES: a small Lua table standing for an unchangeable Java copy (in a slot, see core.lua). A part loads
-- whole on its first touch, as a real Lua table; parts never touched go back to Java as "unchanged" (free).
--   item_stack(x)    x: an item_stack (gives a copy), vanilla item text "diamond_sword[damage=5]", or a codec table
--                    { id = , count = , components = } (vanilla's ItemStack codec). The empty stack is nil.
--                    it.id, it.count, it.components (read and write; your copy). As vanilla as possible: no checks
--                    of Lullaby's own.
--   it.components    EXACTLY vanilla's patch: only the changes against the item type's defaults, keys as vanilla
--                    writes them ("minecraft:custom_name"); a removed default is "!minecraft:food" = {}. Short keys
--                    work too (it.components.custom_name, it.components["!food"] = {}): a lookup only, nothing is
--                    added. Strings are literal text, as vanilla (no MiniMessage inside tables).
--   tostring(it)     vanilla's item text with the changed components (as /give takes it; no count, like vanilla)
--   a == b           vanilla's ItemStack.matches (same item, count and components)
--   component(x)     text: MiniMessage string, codec table or component -> a PLAIN changeable table in vanilla's
--                    codec shape (c.text, c.color, c.extra, ...; strings literal). tostring gives MiniMessage; ==
--                    compares that. A show_item hover holds item = <item_stack>.
--   from_json(text, type)  datapack JSON with vanilla's own meaning; type "item_stack" or "component".
--   pairs, next      Lullaby's own: on a lazy value they load every part first; on other tables plain Lua.
local J, lib, I = ...

local buffer = require("string.buffer")
-- LuaJIT's 64-bit whole numbers (vanilla longs above 2^53) can only be DECODED from the wire once ffi's type state
-- exists. The library loads it for itself; scripts still get no ffi.
require("ffi")
local type, error, setmetatable, rawget, rawset, raw_next, pcall, tostring =
	type, error, setmetatable, rawget, rawset, next, pcall, tostring
-- Private copies: scripts get the real string table and could change it for everyone.
local find, sub, gsub, format = string.find, string.sub, string.gsub, string.format
local kind_by_meta, slot_of = I.kind_by_meta, I.slot_of
local meta_of = I.debug_getmetatable

-- Java owns the kind list (KINDS in nms/Codecs.java); Java gets the 0-based index of a kind on the wire.
local KINDS = {}
for name in string.gmatch(J.wire_kinds(), "[^\n]+") do KINDS[#KINDS + 1] = name end

-- Each kind's metatable, in KINDS order. Built once after the whole library has loaded (I.check_kinds, from
-- I.setup): a Java kind without a Lua side is a Lullaby bug and stops the load instead of quietly never matching.
local function kind_metas()
	local by_name = {}
	for meta, name in raw_next, kind_by_meta do by_name[name] = meta end
	for name, meta in raw_next, I.object_meta do by_name[name] = meta end
	local metas, missing = {}, {}
	for i = 1, #KINDS do
		metas[i] = by_name[KINDS[i]]
		if not metas[i] then missing[#missing + 1] = KINDS[i] end
	end
	return metas, missing
end

-- item_stacks inside a table (a show_item hover's item = ...) need their CURRENT Java slot on the wire: mark() finds
-- them (each table visited once, so loops can't hang it), puts the slot at it[true] for the encoder and remembers
-- what to undo. Small values (vec3, ...) are not looked into, so a list of 4096 vec3 costs one check per entry.
local marked, helpers, seen = {}, {}, {}
local ITEM -- set below
local function mark(t, level)
	seen[t] = true
	if level > 100 then return end -- the encoder reports "too deep"
	for key, value in raw_next, t do
		if type(value) == "table" and not seen[value] then
			local meta = meta_of(value)
			if meta == ITEM then
				seen[value] = true
				local slot, helper = I.item_java(value)
				rawset(value, true, slot)
				marked[#marked + 1] = value
				if helper then helpers[#helpers + 1] = helper end
			elseif meta == nil or kind_by_meta[meta] == "component" or kind_by_meta[meta] == "components" then
				mark(value, level + 1)
			end
		end
	end
end

function I.check_kinds()
	local _, missing = kind_metas()
	-- FORMAT: Java and Lua disagree on the wire's kinds (a bug in Lullaby, not in a script)
	if #missing > 0 then error("Lullaby bug: wire kinds without a Lua side: " .. table.concat(missing, ", "), 0) end
end

local depth = 0 -- I.wire calls itself while marking (a changed item_stack is built through the wire)
local encoder
-- Any Lua value as wire bytes. Errors (functions, coroutines, tables inside themselves, too deep) say what failed.
-- Helper copies of changed item_stacks inside stay alive until the next outer I.wire call: send the bytes first.
function I.wire(value)
	if not encoder then encoder = buffer.new({ metatable = (kind_metas()) }) end
	if depth == 0 then
		for i = #helpers, 1, -1 do I.release(helpers[i]); helpers[i] = nil end
	end
	depth = depth + 1
	local first = #marked + 1
	local ok, err = true, nil
	if type(value) == "table" then ok, err = pcall(mark, value, 1) end
	local bytes
	if ok then
		encoder:reset()
		ok, err = pcall(encoder.encode, encoder, value)
		if ok then bytes = encoder:get() end
	end
	for i = #marked, first, -1 do rawset(marked[i], true, nil); marked[i] = nil end
	depth = depth - 1
	if depth == 0 then for t in raw_next, seen do seen[t] = nil end end
	if not ok then
		err = gsub(tostring(err), "^[^:]*:%d+: ", "")
		-- FORMAT: a table that holds itself, or is too deep, sent to the game
		if find(err, "too deep", 1, true) then
			err = "the table holds itself (a loop) or is nested more than 100 levels deep"
		end
		error(err, 0)
	end
	if #bytes > 1048576 then encoder:free() end -- don't keep a huge buffer until reload
	return bytes
end

-- ---------------------------------------------------------------------------------------------------------------
-- components: vanilla's patch as a plain table; short keys are looked up with "minecraft:" in front.
-- ---------------------------------------------------------------------------------------------------------------

local function full_key(key)
	if type(key) ~= "string" or find(key, ":", 1, true) then return nil end
	if sub(key, 1, 1) == "!" then return "!minecraft:" .. sub(key, 2) end
	return "minecraft:" .. key
end

local COMPONENTS = {
	__index = function(t, key)
		local full = full_key(key)
		if full then return rawget(t, full) end
		return nil
	end,
	__newindex = function(t, key, value) rawset(t, full_key(key) or key, value) end,
	__metatable = false,
}

-- A plain table given as it.components becomes a COMPONENTS table, short keys turned into full ids.
local function as_components(t)
	if meta_of(t) == COMPONENTS then return t end
	local result = setmetatable({}, COMPONENTS)
	for key, value in raw_next, t do result[key] = value end
	return result
end
kind_by_meta[COMPONENTS] = "components"

-- ---------------------------------------------------------------------------------------------------------------
-- item_stack
-- ---------------------------------------------------------------------------------------------------------------

ITEM = { __metatable = false }
kind_by_meta[ITEM] = "item_stack"
local loaded = setmetatable({}, { __mode = "k" })   -- item_stack -> { part = true } for parts read or written
local written = setmetatable({}, { __mode = "k" })  -- item_stack -> { part = true } for parts set by the script
local original = setmetatable({}, { __mode = "k" }) -- item_stack -> components as wire bytes right after loading

local PARTS = {
	id = J.item_id,
	count = J.item_count,
	components = function(slot) return setmetatable(buffer.decode(J.item_components(slot)), COMPONENTS) end,
}
-- FORMAT: a script writes an unknown item_stack field
local NO_FIELD = "item_stack has no field %s (id, count, components)"

-- A new item_stack for a fresh Java slot; nil (and the slot freed) for the empty stack.
local function new_item(slot)
	local it = setmetatable({}, ITEM)
	I.give_slot(it, slot)
	if J.item_empty(slot) then return nil end -- `it` is dropped: its slot is freed with it
	loaded[it], written[it] = {}, {}
	return it
end
I.new_item = new_item

ITEM.__index = function(it, key)
	local load = PARTS[key]
	if not load then I.no_field("item_stack", key, "id, count, components", 2) end
	local parts = loaded[it]
	if parts[key] then return nil end -- set to nil by the script
	local value = load(slot_of[it])
	parts[key] = true
	-- The components table can be changed in place, which Lua can't see: keep its bytes now, compare later.
	if key == "components" then original[it] = I.wire(value) end
	rawset(it, key, value)
	return value
end

ITEM.__newindex = function(it, key, value)
	if not PARTS[key] then error(format(NO_FIELD, tostring(key)), 2) end
	if key == "components" and type(value) == "table" then value = as_components(value) end
	loaded[it][key] = true
	written[it][key] = true
	rawset(it, key, value)
end

-- The Java slot holding this stack as it is now: its own slot if nothing changed (reading alone changes nothing),
-- else a new copy built from the changed parts. Returns the slot and a helper object owning that new copy (nil if
-- none): the caller frees it with I.release right after its Java call.
-- Whether this part of a loaded item differs from the Java copy (reading alone changes nothing).
local function part_changed(it, part, value)
	local send = written[it][part]
	if part == "components" and not send and value ~= nil then send = I.wire(value) ~= original[it] end
	return send
end

-- Whether the script changed this item_stack since it was made (event lists use it to see a change).
function I.item_changed(it)
	for part in raw_next, loaded[it] do
		if part_changed(it, part, rawget(it, part)) then return true end
	end
	return false
end

function I.item_java(it)
	local changed, any = {}, false
	for part in raw_next, loaded[it] do
		local value = rawget(it, part)
		local send = part_changed(it, part, value)
		if send then
			-- FORMAT: a part set to nil
			if value == nil then error("item_stack: " .. part .. " can't be nil" .. (part == "components"
				and " (use {} for no changes)" or ""), 3) end
			changed[part] = value
			any = true
		end
	end
	if not any then return slot_of[it], nil end
	local slot = J.item_build(slot_of[it], I.wire(changed))
	local helper = setmetatable({}, ITEM) -- never seen by scripts
	I.give_slot(helper, slot)
	return slot, helper
end

-- Runs f(slot) with the item's current Java slot and frees any helper copy at once.
local function with_slot(it, f, other)
	local slot, helper = I.item_java(it)
	local ok, result = pcall(f, slot, other)
	if helper then I.release(helper) end
	if not ok then error(result, 0) end
	return result
end

ITEM.__tostring = function(it) return with_slot(it, J.item_text) end

ITEM.__eq = function(a, b)
	if meta_of(a) ~= ITEM or meta_of(b) ~= ITEM then return false end
	return with_slot(a, function(sa) return with_slot(b, function(sb) return J.item_matches(sa, sb) end) end)
end

--[[doc
name: item_stack( )
kind: reading
group: Items
words: item give stack
syntax: item_stack( "diamond_sword[damage=5]" | table | item_stack )
lead: An item stack from vanilla's item text, a table, or a copy of another item stack.
arg: item | text · table · item_stack | The item.
returns: item_stack
expert: Thread | main thread, like every script
expert: Cost | 10–100 µs to make one; it.id 1–5 µs; it.components first touch 10–100 µs (guess, not measured)
expert: Under the hood | vanilla's item parser and ItemStack codec; a Java copy in a slot, parts load on first touch
expert: Can fail | unknown item or component, values vanilla's codec refuses (vanilla's message)
expert: Reload | item stacks are copies: they go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.item_stack(x)
	local t = type(x)
	if t == "string" then return new_item(J.item_parse(x)) end
	if t == "table" then
		if meta_of(x) == ITEM then return with_slot(x, function(slot) return new_item(J.slot_copy(slot)) end) end
		return new_item(J.item_from_table(I.wire(x)))
	end
	-- FORMAT: wrong argument for item_stack()
	error("item_stack needs item text like \"diamond_sword[damage=5]\", a table or an item_stack, got "
		.. I.type_name(x), 2)
end

-- ---------------------------------------------------------------------------------------------------------------
-- component (text)
-- ---------------------------------------------------------------------------------------------------------------

-- A component is a plain Lua table in vanilla's codec shape ({ text = "Hi", color = "red", extra = { ... } }), free
-- to change; only the top table carries COMPONENT (for type_of, tostring and ==). Strings in it are literal text, as
-- vanilla reads them. A show_item hover holds item = <item_stack> instead of vanilla's flat id/count/components.
local COMPONENT = {
	__tostring = function(c) return J.component_text(I.wire(c)) end,
	__metatable = false,
}
COMPONENT.__eq = function(a, b)
	if meta_of(a) ~= COMPONENT or meta_of(b) ~= COMPONENT then return false end
	return J.component_text(I.wire(a)) == J.component_text(I.wire(b))
end
kind_by_meta[COMPONENT] = "component"

-- Puts item_stacks where Java left a slot number ({ [true] = slot }) in show_item hovers.
local function items_in(t)
	if rawget(t, "action") == "show_item" then
		local ref = rawget(t, "item")
		if type(ref) == "table" and type(rawget(ref, true)) == "number" then rawset(t, "item", new_item(rawget(ref, true))) end
	end
	for _, value in raw_next, t do
		if type(value) == "table" and meta_of(value) == nil then items_in(value) end
	end
end

-- Java's answer (wire bytes, number of item slots in it) as a new component table.
local function new_component(bytes, items)
	local c = buffer.decode(bytes)
	if items > 0 then items_in(c) end
	return setmetatable(c, COMPONENT)
end
I.new_component = new_component

-- A codec table Java built inside a bigger tree (saved data, see data.lua) as a component.
function I.as_component(c)
	items_in(c)
	return setmetatable(c, COMPONENT)
end

-- Wire bytes from Java as Lua values (the other direction of I.wire).
I.decode = buffer.decode

-- component("<red>Hi") reads MiniMessage (the only place that does); component(table or component) is a checked
-- fresh copy, cleaned up by vanilla's codec.
--[[doc
name: component( )
kind: reading
group: Text
words: minimessage text format color
syntax: component( "<red>Hi" | table | component )
lead: Text as a component. Text is read as MiniMessage (the only place that does); a table or component is a checked fresh copy.
arg: text | text · table · component | The text.
returns: component
expert: Thread | main thread, like every script
expert: Cost | MiniMessage text 10–200 µs, a table 10–100 µs (guess, not measured)
expert: Under the hood | MiniMessage (text only) or vanilla's component codec; the result is a plain Lua table
expert: Can fail | fields vanilla's codec refuses
expert: Reload | plain tables: they go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.component(x)
	local t = type(x)
	if t == "string" then return new_component(J.component_mini(x)) end
	if t == "table" then return new_component(J.component_from_table(I.wire(x))) end
	-- FORMAT: wrong argument for component()
	error("component needs MiniMessage text, a table or a component, got " .. I.type_name(x), 2)
end

--[[doc
name: from_json( )
kind: reading
group: Items
words: json parse
syntax: from_json( text, "item_stack" | "component" )
lead: Reads vanilla's JSON as an item stack or a component.
arg: text | text | The JSON.
arg: type_name | text | "item_stack" or "component".
returns: item_stack · component
expert: Thread | main thread, like every script
expert: Cost | 20–300 µs, more for big JSON (guess, not measured)
expert: Under the hood | vanilla's JSON reader and codec, as datapacks use them
expert: Can fail | bad JSON, a type other than item_stack or component, values vanilla refuses
expert: Reload | results go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.from_json(text, type_name)
	-- FORMAT: wrong arguments for from_json()
	if type(text) ~= "string" then error("from_json needs JSON text, got " .. I.type_name(text), 2) end
	if type_name ~= "item_stack" and type_name ~= "component" then
		error("from_json: type must be \"item_stack\" or \"component\", got " .. tostring(type_name), 2)
	end
	if type_name == "item_stack" then return new_item(J.from_json(text, type_name)) end
	return new_component(J.from_json(text, type_name))
end

-- ---------------------------------------------------------------------------------------------------------------
-- pairs and next (LuaJIT ignores __pairs, so Lullaby has its own; core.lua puts them into the sandbox)
-- ---------------------------------------------------------------------------------------------------------------

local function load_all(t)
	local meta = meta_of(t)
	if meta == ITEM then
		for part in raw_next, PARTS do local _ = t[part] end
	elseif meta == I.STATE_META then
		return false
	end
	return true
end

-- FORMAT: pairs/next given something that is not a table (the same words as Lua's own message)
local NOT_TABLE = "bad argument #1 to '%s' (table expected, got %s)"

function I.next(t, key)
	if type(t) ~= "table" then error(format(NOT_TABLE, "next", I.type_name(t)), 2) end
	if not load_all(t) then return nil end
	local k, v = raw_next(t, key)
	return k, v
end

local function nothing() return nil end
function I.pairs(t)
	if type(t) ~= "table" then error(format(NOT_TABLE, "pairs", I.type_name(t)), 2) end
	if not load_all(t) then return nothing, t, nil end
	return raw_next, t, nil
end
