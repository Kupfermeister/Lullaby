-- Lullaby library: the raw Java door (config unlock.java; Java side: JavaDoor.java).
--
-- Off by default. Then this file only defines I.java_door, which nothing calls: no table, no Java function, no check
-- exists. core.lua's sandbox calls I.java_door() once when unlock.java is true and gives scripts the result as `java`.
-- The library itself never uses the door (library features go through J and nms).
--
-- Raw objects are tables with a hidden metatable; their Java object lives in a slot (Slots.java) like every Lullaby
-- object, so the garbage collector frees it and a reload throws all of it away. Only nil, true/false, numbers, text
-- and raw objects go to Java: never a Lua function, so Java can never call a script back (see the docs page).
--   java.class("org.bukkit.Bukkit") -> class C     C:staticMethod(...)  C.STATIC_FIELD  C:new(...)
--   obj:method(...)  obj.field  obj.field = v  arr[1] (from 1, like Lua)  arr.length
--   java.raw(p | world | block | item_stack | event) -> raw object      java.wrap(raw) -> Lullaby object or nil
local J, lib, I = ...

function I.java_door()
	local D = J.java_door() -- JavaDoor.java: the door's Java functions, made only now
	local type, error, select, unpack, setmetatable, tostring = type, error, select, unpack, setmetatable, tostring
	local kind_of, slot_of = I.kind_of, I.slot_of
	local WEAK_KEYS = { __mode = "k" }

	local class_of = setmetatable({}, WEAK_KEYS) -- raw object -> its class object
	local class_id = {}                          -- class object -> its number in JavaDoor (strong: classes stay)
	local classes = {}                           -- number -> class object
	local members = {}                           -- class object -> { [true] = statics, [false] = instance } name -> "m"/"f"

	local RAW, CLASS = {}, {}
	I.kind_by_meta[RAW], I.kind_by_meta[CLASS] = "java", "java_class"

	local function class_by_id(id)
		local c = classes[id]
		if not c then
			c = setmetatable({}, CLASS)
			class_id[c] = id
			kind_of[c] = "java_class"
			I.give_slot(c, D.class_slot(id)) -- the java.lang.Class, so a class can be an argument
			members[c] = { [true] = {}, [false] = {} }
			classes[id] = c
		end
		return c
	end

	local function new_raw(slot, id)
		local o = setmetatable({}, RAW)
		kind_of[o] = "java"
		I.give_slot(o, slot)
		class_of[o] = class_by_id(id)
		return o
	end

	-- Java answers with a kind number: 0 = a plain value (or nothing: nil), 1 = a raw object as slot and class number.
	local function result(kind, a, b)
		if kind == 1 then return new_raw(a, b) end
		return a
	end

	-- What can't go to Java, in plain words (Lua checks these: Java can't see a function or a Lullaby object).
	local function refuse(what, i, v)
		local t = I.type_name(v)
		-- FORMAT: a value that can't be an argument of a raw Java call
		if type(v) == "function" then
			error(what .. ": argument " .. i .. " is a function, and Java can never call a script", 3)
		end
		if kind_of[v] == "player" or kind_of[v] == "world" or kind_of[v] == "block" or kind_of[v] == "event"
			or t == "item_stack" then
			error(what .. ": argument " .. i .. " is a Lullaby " .. t .. ", give java.raw(x) instead", 3)
		end
		error(what .. ": argument " .. i .. " can't go to Java (a " .. t .. "; Java takes nil, true/false, numbers, "
			.. "text and raw Java objects)", 3)
	end

	-- The arguments in a reused list, raw objects as their slots; mask bit i-1 marks them. No garbage per call.
	local scratch = {}
	local MAX = 50 -- the mask is one Lua number: 53 bits are exact
	local function prepare(what, ...)
		local n = select("#", ...)
		-- FORMAT: too many arguments for a raw Java call
		if n > MAX then error(what .. ": at most " .. MAX .. " arguments, got " .. n, 3) end
		local mask, bit = 0, 1
		for i = 1, n do
			local v = select(i, ...)
			local t = type(v)
			if t == "table" and (class_of[v] or class_id[v]) then
				v = slot_of[v]
				mask = mask + bit
			elseif t ~= "nil" and t ~= "boolean" and t ~= "number" and t ~= "string" then
				refuse(what, i, v)
			end
			scratch[i] = v
			bit = bit * 2
		end
		return mask, n
	end

	-- One function per method name, shared by every object and class: o:name(...) and C:name(...).
	local methods = {}
	local function method(name)
		local f = methods[name]
		if not f then
			f = function(self, ...)
				local mask, n = prepare(name, ...)
				if class_of[self] then return result(D.call(slot_of[self], name, mask, unpack(scratch, 1, n))) end
				if class_id[self] then return result(D.static_call(class_id[self], name, mask, unpack(scratch, 1, n))) end
				-- FORMAT: a method called with . instead of :
				error(name .. " is a Java method: call it with a colon, obj:" .. name .. "(...)", 2)
			end
			methods[name] = f
		end
		return f
	end

	local function constructor(self, ...)
		local id = class_id[self]
		-- FORMAT: new called with . instead of :
		if not id then error("new is called with a colon: C:new(...)", 2) end
		local mask, n = prepare("new", ...)
		return result(D.new(id, mask, unpack(scratch, 1, n)))
	end

	-- "m" a method, "f" a field; asked from Java once per class and name, then kept.
	local function member(c, statics, name)
		local known = members[c][statics]
		local kind = known[name]
		if kind == nil then
			kind = D.member(class_id[c], statics, name)
			if not kind then
				-- FORMAT: a name a raw object or class doesn't have
				error(tostring(D.class_name(class_id[c])) .. " has no public " .. (statics and "static " or "")
					.. "field or method " .. name, 3)
			end
			known[name] = kind
		end
		return kind
	end

	local function key_text(key)
		-- FORMAT: a raw object indexed by something that is neither a name nor an array index
		if type(key) ~= "string" then error("raw Java objects take names and array numbers, got " .. I.type_name(key), 3) end
		return key
	end

	RAW.__index = function(o, key)
		if type(key) == "number" then return result(D.index(slot_of[o], key)) end
		key = key_text(key)
		if member(class_of[o], false, key) == "m" then return method(key) end
		return result(D.get(slot_of[o], key))
	end
	RAW.__newindex = function(o, key, value)
		local mask = prepare(tostring(key), value)
		if type(key) == "number" then D.set_index(slot_of[o], key, mask, scratch[1]) return end
		key = key_text(key)
		member(class_of[o], false, key)
		D.set(slot_of[o], key, mask, scratch[1])
	end
	RAW.__tostring = function(o) return D.text(slot_of[o]) end
	RAW.__eq = function(a, b) return D.same(slot_of[a], slot_of[b]) end -- Java's equals
	RAW.__metatable = false

	CLASS.__index = function(c, key)
		key = key_text(key)
		if key == "new" then return constructor end -- `new` is a Java keyword: no method or field has that name
		if member(c, true, key) == "m" then return method(key) end
		return result(D.static_get(class_id[c], key))
	end
	CLASS.__newindex = function(c, key, value)
		key = key_text(key)
		local mask = prepare(key, value)
		member(c, true, key)
		D.static_set(class_id[c], key, mask, scratch[1])
	end
	CLASS.__tostring = function(c) return "class " .. D.class_name(class_id[c]) end -- FORMAT
	CLASS.__metatable = false

	local java = {}

--[[doc
id: java.class
name: java.class( )
kind: reading
group: Java door
words: raw java class import reflection bukkit paper nms
syntax: java.class( "org.bukkit.Bukkit" ) → java_class
lead: Loads a Java class by its full name: Paper's and Bukkit's, Minecraft's own (net.minecraft...), Java's, or any plugin's. Call its static methods with a colon (C:getServer()), read static fields with a dot (C.STONE), make objects with C:new(...). Only with unlock.java in the config.
arg: name | text · java | The full class name, or a raw java.lang.Class (from obj:getClass()).
returns: java_class
example:
  local Bukkit = java.class("org.bukkit.Bukkit")
  print(Bukkit:getTPS()[1]) -- the server's TPS over the last minute
expert: Thread | main thread, like every script
expert: Cost | first load of a class 10 µs–5 ms, then 1–10 µs; a method call 1–20 µs (guess, not measured)
expert: Under the hood | LuaJava reflection through JavaDoor.java
expert: Can fail | unknown class; Java exceptions become red errors
expert: Reload | class objects go with the Lua state; game changes stay
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
	function java.class(name)
		if type(name) == "string" then return class_by_id(D.class(name)) end
		if class_of[name] then return class_by_id(D.class_of(slot_of[name])) end
		if class_id[name] then return name end
		-- FORMAT: java.class( ) given something else
		error("java.class needs a full class name like \"org.bukkit.Bukkit\", got " .. I.type_name(name), 2)
	end

--[[doc
id: java.raw
name: java.raw( )
kind: reading
group: Java door
words: raw java bukkit player world block event item unwrap handle
syntax: java.raw( x ) → java | nil
lead: The Java object behind a Lullaby object: a player gives Bukkit's Player (an OfflinePlayer when offline), a world Bukkit's World, a block Bukkit's Block, an item_stack a Bukkit ItemStack copy, an event the Paper event itself (only while its handler runs). nil for a world or block whose world isn't loaded. Only with unlock.java in the config.
arg: x | player · world · block · item_stack · event | The Lullaby object.
returns: java · nil
example:
  after{ "PlayerJoinEvent", function(e)
    java.raw(e.player):setPlayerListName("[new] " .. e.player.name)
  end }
expert: Thread | main thread, like every script
expert: Cost | 1–10 µs (guess, not measured)
expert: Under the hood | the object's slot in Java, handed out raw
expert: Can fail | a value with no Java object behind it
expert: Reload | raw objects go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
	function java.raw(x)
		if class_of[x] or class_id[x] then return x end
		local kind = kind_of[x]
		if kind == "player" or kind == "world" or kind == "event" then
			local slot = slot_of[x]
			-- FORMAT: java.raw( ) of an event whose handlers ended, or of the reload event
			if not slot then error("java.raw: this event has no Java event now (it is over, or it is Lullaby's own)", 2) end
			return result(D.raw(kind, slot))
		end
		if kind == "block" then
			local pos = x.pos
			return result(D.raw("block", slot_of[x.world], pos[1], pos[2], pos[3]))
		end
		if I.type_name(x) == "item_stack" then
			local slot, helper = I.item_java(x)
			local ok, kind_, a, b = pcall(D.raw, "item_stack", slot)
			if helper then I.release(helper) end
			if not ok then error(kind_, 0) end
			return result(kind_, a, b)
		end
		-- FORMAT: java.raw( ) of something without a Java side
		error("java.raw needs a player, world, block, item_stack or event, got " .. I.type_name(x), 2)
	end

--[[doc
id: java.wrap
name: java.wrap( )
kind: reading
group: Java door
words: raw java bukkit player world block location item wrap
syntax: java.wrap( obj ) → player · world · block · location · item_stack · nil
lead: The Lullaby object for a raw Java object, where Lullaby has one: a Bukkit Player or OfflinePlayer gives a player, a World a world, a Block a block, a Location a location, a Bukkit or Minecraft ItemStack an item_stack copy. nil for anything else. Only with unlock.java in the config.
arg: obj | java | A raw Java object.
returns: player · world · block · location · item_stack · nil
example:
  local Bukkit = java.class("org.bukkit.Bukkit")
  local p = java.wrap(Bukkit:getPlayer("Notch")) -- nil if Notch is offline
expert: Thread | main thread, like every script
expert: Cost | 1–10 µs; an item stack is copied: 10–50 µs (guess, not measured)
expert: Under the hood | JavaDoor.java turns a known Bukkit or Minecraft object into its Lullaby object
expert: Can fail | never; unknown objects give nil
expert: Reload | results go with the Lua state
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
	function java.wrap(o)
		if o == nil then return nil end
		-- FORMAT: java.wrap( ) of something that isn't a raw Java object
		if not class_of[o] then error("java.wrap needs a raw Java object, got " .. I.type_name(o), 2) end
		return I.from_java(D.wrap(slot_of[o]))
	end

	return setmetatable(java, {
		-- FORMAT: a name the java table doesn't have
		__index = function(_, key) error("java has no " .. tostring(key) .. " (class, raw, wrap)", 2) end,
		__newindex = function(_, key) error("java can't be changed (java." .. tostring(key) .. ")", 2) end,
		__metatable = false,
	})
end

--[[doc
id: java
name: the Java door
kind: lua
group: Java door
words: raw java unlock reflection bukkit paper nms plugin api expert tps
syntax: unlock = { java = true }   (plugins/lullaby/config.lua)
lead: For experts who need something the library doesn't have yet: scripts use any Java directly, from Paper's and Bukkit's API, Minecraft's own classes or other plugins. Locked by default; with java = true under unlock in the config, every script gets the table java: java.class( ) loads a class, java.raw( ) gives the Java object behind a player, world, block, item_stack or event, and java.wrap( ) turns one back. Objects work as in Java, with a colon for methods: obj:getName(), obj.field, obj.field = v, C:new(...). Arrays count from 1, like Lua tables: arr[1] is Java's arr[0], arr.length their size. nil, true/false, numbers and text cross as Lua values (Java's whole numbers past 2^53 lose their last digits), everything else stays a raw Java object.
note: Unlocking java is like unlocking io, os and ffi at once, for every script on the server, and raw code can break on any Minecraft update. The details are below.
expert: What it allows |everything Java can do. Unlocking it is like unlocking io, os and ffi at once: a script can read and delete any file, run programs, change any plugin, and reach into Lullaby itself. Unlock it only when every script on the server is trusted. Only public methods and fields are reachable.
expert: What it can break | anything. Raw code is not checked the way library functions are: a wrong call can corrupt a world, kick every player or crash the server. Nothing is promised across updates: Minecraft's own classes (net.minecraft...) change names on almost every update, and Paper's API changes too. Lullaby checks none of it at startup.
expert: Errors | a Java exception becomes the normal red error at the script's line, with what was called and Java's own message (org.bukkit.Server:getPlayer failed: IllegalArgumentException: ...), never a Java stack trace. Wrong arguments name what the method takes: ... can't take (number); it takes (String) or (UUID).
expert: Time limit | every raw call checks script_time_limit like library calls do, so an endless loop through raw calls is stopped. A single Java call that never returns (Thread.sleep, waiting on a lock) can't be stopped.
expert: Threads | scripts run on the server main thread, and so does every raw call. A Lua function can never be given to Java, so Java can never call a script back: no hand-made Bukkit listeners, no Runnables, no scheduler tasks. A script can still start a Java thread or an async task through the door; Lullaby can't prevent that, and what such code does with game objects off the main thread is not safe.
expert: Reload | raw objects belong to the Lua state and go with it on /lullaby reload. What a script changed in the game stays changed, as with any game change: a world's time, a registered scoreboard team or recipe, another plugin's settings. Lullaby can't see those and undoes none of them.
expert: Speed | How fast a raw call is compared to a library call is not measured yet.
example:
  -- config.lua: unlock = { java = true }
  print(java.class("org.bukkit.Bukkit"):getTPS()[1]) -- the server's TPS, which the library can't read yet
status: unconfirmed
]]
