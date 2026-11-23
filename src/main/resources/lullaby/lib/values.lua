-- Lullaby library: small values (design: tools/docs/design/types.lua).
--
-- Positions and rotations are plain Lua tables with a shared metatable. All math is plain Lua: nothing here calls
-- Java, so thousands of points per tick stay cheap. Values are COPIES and can't be changed: v.x = 5 is an error,
-- make a new value instead (v + vec3(5, 0, 0), p:offset(...)). Every value is checked completely where it is made
-- (type, NaN, infinity, whole numbers, int range), never again in the math, so loops stay fast.
--
--   block_pos(x, y, z)       whole numbers (vanilla BlockPos)    + - ==  :offset(x, y, z) :vec3() :chunk_pos()
--                                                                       :section_pos()
--   section_pos(x, y, z)     whole numbers (vanilla SectionPos: a 16x16x16 cube)   ==  :offset  :chunk_pos()
--                                                                       :contains(block_pos)
--   chunk_pos(x, z)          whole numbers (vanilla ChunkPos: a 16x16 column)      ==  :offset(x, z)
--   vec3(x, y, z)            decimals (vanilla Vec3; also Vector3f fields and armor stand Rotations)
--                            + - * / (by a number) unary - ==  :length() :normalize() :dot(v) :cross(v)
--                            :distance_to(v) :lerp(v, t) :x_rot(degrees) :y_rot(degrees) :z_rot(degrees) :rotate(quaternion) :block_pos()
--   yaw_pitch(yaw, pitch)    degrees  ==  :direction() -> vec3;  yaw_pitch.from_direction(vec3)
--   quaternion(x, y, z, w)   * (combine) ==  :rotate(vec3);  quaternion.from_yaw_pitch(yp),
--                            quaternion.from_axis_angle(axis vec3, degrees)
--   aabb(min, max)           vec3 corners, max side open (vanilla AABB)  ==  :contains(vec3) :intersects(aabb)
--                            :grow(amount) :move(vec3) :center() :size()
--   bounding_box(min, max)   block_pos corners, both sides included (vanilla BoundingBox)  ==
--                            :contains(block_pos) :intersects(bounding_box) :center()
--   global_pos(dimension, block_pos)   a block position in a dimension (vanilla GlobalPos); dimension is an id
--   location(world, vec3, yaw_pitch)   world + exact position + direction (yaw_pitch optional: 0, 0)
--                                      .block -> the block object at it (nil: chunk not loaded)
--                                      ==  :chunk() -> its chunk  :offset(vec3)
-- Each maker also takes a value of its own type (returned as is: values never change), a table { 1, 2, 3 } or
-- { x = , y = , z = } (named like the fields), and for the position types vanilla's command text ("1 64 -3").
-- Errors are raised without a position (level 0): core.lua's report finds the script line itself, and a script that
-- catches one with pcall gets the clean text.
-- Angles are degrees everywhere (vanilla's Vec3.xRot uses radians; Lullaby keeps one unit).
local J, lib, I = ...

local type, error, setmetatable, tonumber, tostring, select, rawget = type, error, setmetatable, tonumber, tostring, select, rawget
local floor, sqrt, sin, cos, atan2, asin, abs, huge, pi = math.floor, math.sqrt, math.sin, math.cos, math.atan2,
	math.asin, math.abs, math.huge, math.pi
local concat, pairs, unpack = table.concat, pairs, unpack
local number_text = I.number_text
local RAD, DEG = pi / 180, 180 / pi
local INT_MIN, INT_MAX = -2147483648, 2147483647

-- Metatable -> type name, for type_of (core.lua reads it): no bookkeeping per value.
local kind_by_meta = I.kind_by_meta

-- ---------------------------------------------------------------------------------------------------------------
-- Checks (only where values are made)
-- ---------------------------------------------------------------------------------------------------------------

-- A number from a script: a Lua number, or a LuaJIT 64-bit number that fits exactly. NaN and infinity are refused.
local function number(value, what, field)
	if type(value) ~= "number" then
		if I.is_int64(value) then
			local n = tonumber(value)
			if n >= -2 ^ 53 and n <= 2 ^ 53 then return n end
			error(what .. ": " .. field .. " is too big for a position (" .. lib.tostring(value) .. ")", 0)
		end
		error(what .. ": " .. field .. " must be a number, got " .. I.type_name(value), 0)
	end
	if value ~= value then error(what .. ": " .. field .. " is not a number (NaN)", 0) end
	if value == huge or value == -huge then error(what .. ": " .. field .. " can't be infinite", 0) end
	return value
end

-- A whole number in Java's int range (vanilla keeps block, section and chunk positions as ints).
local function whole(value, what, field)
	value = number(value, what, field)
	if value ~= floor(value) then
		error(what .. ": " .. field .. " must be a whole number, got " .. number_text(value), 0)
	end
	if value < INT_MIN or value > INT_MAX then
		error(what .. ": " .. field .. " is out of range (" .. number_text(value) .. ")", 0)
	end
	return value + 0 -- -0 -> 0, so tostring never shows "-0"
end

-- Reads the parts of a value from (a, b, c, ...) positional, { a, b, c }, { name = ... } or vanilla text "a b c".
-- names: field names; check: number or whole. Returns the numbers as multiple values.
local function parts(what, names, check, first, ...)
	local n = #names
	local t = type(first)
	if t == "table" and I.type_name(first) ~= "table" then -- another Lullaby value: converting is explicit
		error(what .. ": got " .. I.type_name(first) .. " (convert it first, e.g. v:block_pos() or p:vec3())", 0)
	end
	if t == "table" then
		if select("#", ...) > 0 then error(what .. ": give either a table or numbers, not both", 0) end
		local named = first[names[1]] ~= nil
		local out = {}
		for i = 1, n do
			local value = named and first[names[i]] or first[i]
			if value == nil then error(what .. ": " .. names[i] .. " is missing", 0) end
			out[i] = value
		end
		for key in pairs(first) do
			local known = false
			for i = 1, n do
				if key == names[i] or key == i then known = true end
			end
			if not known then error(what .. ": unknown field " .. tostring(key), 0) end
			if named and type(key) == "number" or not named and type(key) == "string" then
				error(what .. ": give either { " .. concat(names, ", ") .. " } or { " .. names[1] .. " = ... }, not both", 0)
			end
		end
		for i = 1, n do out[i] = check(out[i], what, names[i]) end
		return unpack(out, 1, n)
	end
	if t == "string" then
		if select("#", ...) > 0 then error(what .. ": give either text or numbers, not both", 0) end
		local out, i = {}, 0
		for word in first:gmatch("%S+") do
			i = i + 1
			if i > n then error(what .. ": too many numbers in \"" .. first .. "\" (needs " .. n .. ")", 0) end
			local value = tonumber(word)
			if not value or word:find("^0[xX]") then
				error(what .. ": \"" .. word .. "\" is not a number (relative ~ and ^ only work in commands)", 0)
			end
			out[i] = value
		end
		if i < n then error(what .. ": needs " .. n .. " numbers, got \"" .. first .. "\"", 0) end
		for k = 1, n do out[k] = check(out[k], what, names[k]) end
		return unpack(out, 1, n)
	end
	local out = { first, ... }
	if select("#", ...) + 1 ~= n then
		error(what .. ": needs " .. n .. " numbers (" .. concat(names, ", ") .. "), got " .. (select("#", ...) + 1), 0)
	end
	for i = 1, n do out[i] = check(out[i], what, names[i]) end
	return unpack(out, 1, n)
end

-- One metatable per type. Fields live in slots [1], [2], ... so that assigning a field (v.x = 5) reaches
-- __newindex and gives an error; reading v.x maps the name to its slot.
-- computed (optional): field name -> function(value), for a field that is worked out on read, not stored
-- (location.block).
local function value_type(name, fields, methods, computed)
	local index_of = {}
	for i = 1, #fields do index_of[fields[i]] = i end
	local has -- "x, y, z, :length(), ..." for the error on a name it doesn't have; made on first need
	local meta = {
		__index = function(v, key)
			local i = index_of[key]
			if i then return rawget(v, i) end -- rawget: a field may be nil (a damage without causing)
			local work = computed and computed[key]
			if work then return work(v) end
			local method = methods[key]
			if method then return method end
			if not has then
				local extra, names = {}, {}
				for k in pairs(computed or {}) do extra[#extra + 1] = k end
				for k in pairs(methods) do names[#names + 1] = ":" .. k .. "()" end
				table.sort(extra)
				table.sort(names)
				has = table.concat(fields, ", ") .. (#extra > 0 and ", " .. table.concat(extra, ", ") or "")
					.. (#names > 0 and ", " .. table.concat(names, ", ") or "")
			end
			I.no_field(name, key, has, 0)
		end,
		__newindex = function(_, key)
			error(name .. " values can't be changed (." .. tostring(key) .. "): make a new one", 0)
		end,
		__metatable = false,
	}
	-- Its own __eq per type: Lua calls __eq only when both sides share it, so a vec3 never equals a block_pos with
	-- the same numbers. Fields compare with ==, so nested values (an aabb's corners) compare by content too.
	local n = #fields
	meta.__eq = function(a, b)
		if I.debug_getmetatable(a) ~= meta or I.debug_getmetatable(b) ~= meta then return false end
		for i = 1, n do
			if rawget(a, i) ~= rawget(b, i) then return false end -- rawget: a field may be nil
		end
		return true
	end
	kind_by_meta[meta] = name
	I.value_fields[name] = fields
	return meta
end

I.value_type = value_type -- entities.lua makes damage with it

local function is(meta, value)
	return type(value) == "table" and I.debug_getmetatable(value) == meta
end

local function need(meta, value, what, name)
	if not is(meta, value) then error(what .. " needs " .. name .. ", got " .. I.type_name(value), 0) end
	return value
end

-- ---------------------------------------------------------------------------------------------------------------
-- block_pos, section_pos, chunk_pos (whole numbers)
-- ---------------------------------------------------------------------------------------------------------------

local block_methods, section_methods, chunk_methods = {}, {}, {}
local BLOCK = value_type("block_pos", { "x", "y", "z" }, block_methods)
local SECTION = value_type("section_pos", { "x", "y", "z" }, section_methods)
local CHUNK = value_type("chunk_pos", { "x", "z" }, chunk_methods)
I.BLOCK_POS_META = BLOCK

-- Makers for values the library already checked (no checks: internal and math results).
local function new_block(x, y, z) return setmetatable({ x, y, z }, BLOCK) end
local function new_section(x, y, z) return setmetatable({ x, y, z }, SECTION) end
local function new_chunk(x, z) return setmetatable({ x, z }, CHUNK) end
I.new_block_pos = new_block
I.new_chunk_pos = new_chunk

-- Math on whole numbers can leave Java's int range: checked on each result.
local function in_range(what, x, y, z)
	if x < INT_MIN or x > INT_MAX or y < INT_MIN or y > INT_MAX or (z and (z < INT_MIN or z > INT_MAX)) then
		error(what .. ": the result is out of range", 0)
	end
end

--[[doc
name: block_pos( )
kind: reading
group: Values
words: position block coordinates xyz
syntax: block_pos( x, y, z ) → block_pos
lead: A block position: whole numbers (vanilla BlockPos). + - == :offset(x, y, z) :vec3() :chunk_pos() :section_pos(). Also takes a value of its own type (returned as is), a table { 1, 2, 3 } or { x = , y = , z = }, or command text like "1 64 -3".
arg: x, y, z | number | Whole numbers.
returns: block_pos
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.block_pos(...)
	local first = ...
	if is(BLOCK, first) and select("#", ...) == 1 then return first end
	return new_block(parts("block_pos", { "x", "y", "z" }, whole, ...))
end
--[[doc
name: section_pos( )
kind: reading
group: Values
words: section cube
syntax: section_pos( x, y, z ) → section_pos
lead: A 16×16×16 cube of blocks: whole numbers (vanilla SectionPos). == :offset(x, y, z) :chunk_pos() :contains(block_pos). Also takes a value of its own type (returned as is), a table { 1, 2, 3 } or { x = , y = , z = }, or command text like "1 4 -3".
arg: x, y, z | number | Whole numbers.
returns: section_pos
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.section_pos(...)
	local first = ...
	if is(SECTION, first) and select("#", ...) == 1 then return first end
	return new_section(parts("section_pos", { "x", "y", "z" }, whole, ...))
end
--[[doc
name: chunk_pos( )
kind: reading
group: Values
words: chunk column
syntax: chunk_pos( x, z ) → chunk_pos
lead: A 16×16 column of blocks: whole numbers (vanilla ChunkPos). == :offset(x, z). Also takes a value of its own type (returned as is), a table { 1, 2 } or { x = , z = }, or command text like "1 -3".
arg: x, z | number | Whole numbers.
returns: chunk_pos
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.chunk_pos(...)
	local first = ...
	if is(CHUNK, first) and select("#", ...) == 1 then return first end
	return new_chunk(parts("chunk_pos", { "x", "z" }, whole, ...))
end

BLOCK.__add = function(a, b)
	need(BLOCK, a, "block_pos +", "two block_pos"); need(BLOCK, b, "block_pos +", "two block_pos")
	local x, y, z = a[1] + b[1], a[2] + b[2], a[3] + b[3]
	in_range("block_pos +", x, y, z)
	return new_block(x, y, z)
end
BLOCK.__sub = function(a, b)
	need(BLOCK, a, "block_pos -", "two block_pos"); need(BLOCK, b, "block_pos -", "two block_pos")
	local x, y, z = a[1] - b[1], a[2] - b[2], a[3] - b[3]
	in_range("block_pos -", x, y, z)
	return new_block(x, y, z)
end
BLOCK.__tostring = function(v) return number_text(v[1]) .. " " .. number_text(v[2]) .. " " .. number_text(v[3]) end

function block_methods.offset(v, dx, dy, dz)
	need(BLOCK, v, "block_pos:offset", "a block_pos (use : not .)")
	dx, dy, dz = whole(dx, "offset", "dx"), whole(dy, "offset", "dy"), whole(dz, "offset", "dz")
	local x, y, z = v[1] + dx, v[2] + dy, v[3] + dz
	in_range("block_pos:offset", x, y, z)
	return new_block(x, y, z)
end
function block_methods.vec3(v)
	need(BLOCK, v, "block_pos:vec3", "a block_pos (use : not .)")
	return I.new_vec3(v[1], v[2], v[3])
end
-- floor(x / 16) is exact for every int (vanilla shifts right by 4, the same result also for negatives).
function block_methods.chunk_pos(v)
	need(BLOCK, v, "block_pos:chunk_pos", "a block_pos (use : not .)")
	return new_chunk(floor(v[1] / 16), floor(v[3] / 16))
end
function block_methods.section_pos(v)
	need(BLOCK, v, "block_pos:section_pos", "a block_pos (use : not .)")
	return new_section(floor(v[1] / 16), floor(v[2] / 16), floor(v[3] / 16))
end

SECTION.__tostring = BLOCK.__tostring
function section_methods.offset(v, dx, dy, dz)
	need(SECTION, v, "section_pos:offset", "a section_pos (use : not .)")
	dx, dy, dz = whole(dx, "offset", "dx"), whole(dy, "offset", "dy"), whole(dz, "offset", "dz")
	local x, y, z = v[1] + dx, v[2] + dy, v[3] + dz
	in_range("section_pos:offset", x, y, z)
	return new_section(x, y, z)
end
function section_methods.chunk_pos(v)
	need(SECTION, v, "section_pos:chunk_pos", "a section_pos (use : not .)")
	return new_chunk(v[1], v[3])
end
function section_methods.contains(v, pos)
	need(SECTION, v, "section_pos:contains", "a section_pos (use : not .)")
	need(BLOCK, pos, "section_pos:contains", "a block_pos")
	return floor(pos[1] / 16) == v[1] and floor(pos[2] / 16) == v[2] and floor(pos[3] / 16) == v[3]
end

CHUNK.__tostring = function(v) return number_text(v[1]) .. " " .. number_text(v[2]) end
function chunk_methods.offset(v, dx, dz)
	need(CHUNK, v, "chunk_pos:offset", "a chunk_pos (use : not .)")
	dx, dz = whole(dx, "offset", "dx"), whole(dz, "offset", "dz")
	local x, z = v[1] + dx, v[2] + dz
	in_range("chunk_pos:offset", x, z)
	return new_chunk(x, z)
end

-- ---------------------------------------------------------------------------------------------------------------
-- vec3 (decimals)
-- ---------------------------------------------------------------------------------------------------------------

local vec_methods = {}
local VEC = value_type("vec3", { "x", "y", "z" }, vec_methods)
I.VEC3_META = VEC

-- Results of math are checked for overflow to infinity (two huge numbers), so a broken vec3 can never exist.
local function new_vec(x, y, z, what)
	if x ~= x or y ~= y or z ~= z or abs(x) == huge or abs(y) == huge or abs(z) == huge then
		error((what or "vec3") .. ": the result is too big or not a number", 0)
	end
	return setmetatable({ x + 0, y + 0, z + 0 }, VEC)
end
I.new_vec3 = new_vec

--[[doc
name: vec3( )
kind: reading
group: Values
words: vector position direction
syntax: vec3( x, y, z ) → vec3
lead: An exact position or direction: decimals (vanilla Vec3). + - * / unary - == :length() :normalize() :dot(other) :cross(other) :distance_to(other) :lerp(other, amount) :x_rot(degrees) :y_rot(degrees) :z_rot(degrees) :rotate(quaternion) :block_pos(). Also takes a value of its own type (returned as is), a table { 1, 2, 3 } or { x = , y = , z = }, or command text like "1.5 64 -3".
arg: x, y, z | number | Decimals.
returns: vec3
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.vec3(...)
	local first = ...
	if is(VEC, first) and select("#", ...) == 1 then return first end
	return new_vec(parts("vec3", { "x", "y", "z" }, number, ...))
end

local function scalar(value, what)
	if type(value) ~= "number" then
		if I.is_int64(value) then return tonumber(value) end
		error(what .. " needs a number, got " .. I.type_name(value), 0)
	end
	if value ~= value or abs(value) == huge then error(what .. ": the number can't be NaN or infinite", 0) end
	return value
end

VEC.__add = function(a, b)
	need(VEC, a, "vec3 +", "two vec3"); need(VEC, b, "vec3 +", "two vec3")
	return new_vec(a[1] + b[1], a[2] + b[2], a[3] + b[3], "vec3 +")
end
VEC.__sub = function(a, b)
	need(VEC, a, "vec3 -", "two vec3"); need(VEC, b, "vec3 -", "two vec3")
	return new_vec(a[1] - b[1], a[2] - b[2], a[3] - b[3], "vec3 -")
end
VEC.__mul = function(a, b)
	if is(VEC, a) then
		local n = scalar(b, "vec3 *")
		return new_vec(a[1] * n, a[2] * n, a[3] * n, "vec3 *")
	end
	local n = scalar(a, "vec3 *")
	need(VEC, b, "vec3 *", "a vec3 and a number")
	return new_vec(b[1] * n, b[2] * n, b[3] * n, "vec3 *")
end
VEC.__div = function(a, b)
	need(VEC, a, "vec3 /", "a vec3 divided by a number")
	local n = scalar(b, "vec3 /")
	if n == 0 then error("vec3 /: division by zero", 0) end
	return new_vec(a[1] / n, a[2] / n, a[3] / n, "vec3 /")
end
VEC.__unm = function(a) return new_vec(-a[1], -a[2], -a[3]) end
VEC.__tostring = BLOCK.__tostring

local function self_vec(v, name) return need(VEC, v, "vec3:" .. name, "a vec3 (use : not .)") end

function vec_methods.length(v)
	self_vec(v, "length")
	return sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
end
-- Like vanilla: a vector shorter than 0.00001 normalizes to zero.
function vec_methods.normalize(v)
	self_vec(v, "normalize")
	local d = sqrt(v[1] * v[1] + v[2] * v[2] + v[3] * v[3])
	if d < 1e-5 then return new_vec(0, 0, 0) end
	return new_vec(v[1] / d, v[2] / d, v[3] / d)
end
function vec_methods.dot(v, w)
	self_vec(v, "dot"); need(VEC, w, "vec3:dot", "a vec3")
	return v[1] * w[1] + v[2] * w[2] + v[3] * w[3]
end
function vec_methods.cross(v, w)
	self_vec(v, "cross"); need(VEC, w, "vec3:cross", "a vec3")
	return new_vec(v[2] * w[3] - v[3] * w[2], v[3] * w[1] - v[1] * w[3], v[1] * w[2] - v[2] * w[1], "vec3:cross")
end
function vec_methods.distance_to(v, w)
	self_vec(v, "distance_to"); need(VEC, w, "vec3:distance_to", "a vec3")
	local dx, dy, dz = v[1] - w[1], v[2] - w[2], v[3] - w[3]
	return sqrt(dx * dx + dy * dy + dz * dz)
end
function vec_methods.lerp(v, w, t)
	self_vec(v, "lerp"); need(VEC, w, "vec3:lerp", "a vec3")
	t = scalar(t, "vec3:lerp")
	return new_vec(v[1] + (w[1] - v[1]) * t, v[2] + (w[2] - v[2]) * t, v[3] + (w[3] - v[3]) * t, "vec3:lerp")
end
-- Rotations about one axis, same direction as vanilla's Vec3.xRot/yRot/zRot, but in degrees.
function vec_methods.x_rot(v, degrees)
	self_vec(v, "x_rot")
	local a = scalar(degrees, "vec3:x_rot") * RAD
	local c, s = cos(a), sin(a)
	return new_vec(v[1], v[2] * c + v[3] * s, v[3] * c - v[2] * s)
end
function vec_methods.y_rot(v, degrees)
	self_vec(v, "y_rot")
	local a = scalar(degrees, "vec3:y_rot") * RAD
	local c, s = cos(a), sin(a)
	return new_vec(v[1] * c + v[3] * s, v[2], v[3] * c - v[1] * s)
end
function vec_methods.z_rot(v, degrees)
	self_vec(v, "z_rot")
	local a = scalar(degrees, "vec3:z_rot") * RAD
	local c, s = cos(a), sin(a)
	return new_vec(v[1] * c + v[2] * s, v[2] * c - v[1] * s, v[3])
end
-- Rounds down like vanilla's BlockPos.containing (floor, also for negatives).
function vec_methods.block_pos(v)
	self_vec(v, "block_pos")
	local x, y, z = floor(v[1]), floor(v[2]), floor(v[3])
	in_range("vec3:block_pos", x, y, z)
	return new_block(x + 0, y + 0, z + 0)
end

-- ---------------------------------------------------------------------------------------------------------------
-- yaw_pitch and quaternion
-- ---------------------------------------------------------------------------------------------------------------

local yp_methods, q_methods = {}, {}
local YP = value_type("yaw_pitch", { "yaw", "pitch" }, yp_methods)
local QUAT = value_type("quaternion", { "x", "y", "z", "w" }, q_methods)
I.YAW_PITCH_META = YP

local function new_yp(yaw, pitch) return setmetatable({ yaw + 0, pitch + 0 }, YP) end
I.new_yaw_pitch = new_yp

--[[doc
id: yaw_pitch
name: yaw_pitch( )
kind: reading
group: Values
words: rotation direction look angle yaw pitch
syntax: yaw_pitch( yaw, pitch ) → yaw_pitch   yaw_pitch.from_direction( vec3 )
lead: A direction as yaw and pitch, in degrees. == :direction() gives the vec3 it looks along (length 1); yaw_pitch.from_direction(direction) goes the other way.
arg: yaw | number | Degrees around the vertical axis.
arg: pitch | number | Degrees up or down.
returns: yaw_pitch
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
lib.yaw_pitch = setmetatable({}, {
	__call = function(_, ...)
		local first = ...
		if is(YP, first) and select("#", ...) == 1 then return first end
		return new_yp(parts("yaw_pitch", { "yaw", "pitch" }, number, ...))
	end,
	__index = yp_methods,
	__newindex = function() error("yaw_pitch can't be changed", 0) end,
	__metatable = false,
})
YP.__tostring = CHUNK.__tostring

-- The direction a yaw/pitch looks at, length 1 (the same formula as vanilla's Vec3.directionFromRotation, but
-- with exact sin/cos instead of vanilla's lookup table).
function yp_methods.direction(r)
	need(YP, r, "yaw_pitch:direction", "a yaw_pitch (use : not .)")
	local yaw, pitch = r[1] * RAD, r[2] * RAD
	local cp = cos(pitch)
	return new_vec(-sin(yaw) * cp, -sin(pitch), cos(yaw) * cp)
end
-- The yaw/pitch that looks along a vec3 (the zero vector gives 0, 0).
function yp_methods.from_direction(v)
	need(VEC, v, "yaw_pitch.from_direction", "a vec3")
	local x, y, z = v[1], v[2], v[3]
	local horizontal = sqrt(x * x + z * z)
	if horizontal == 0 and y == 0 then return new_yp(0, 0) end
	return new_yp(atan2(-x, z) * DEG + 0, atan2(-y, horizontal) * DEG + 0)
end

local function new_q(x, y, z, w) return setmetatable({ x + 0, y + 0, z + 0, w + 0 }, QUAT) end
I.new_quaternion = new_q -- entities.lua: display_data's rotations

--[[doc
id: quaternion
name: quaternion( )
kind: reading
group: Values
words: rotation rotate 3d axis angle
syntax: quaternion( x, y, z, w ) → quaternion   quaternion.from_yaw_pitch( yaw_pitch )   quaternion.from_axis_angle( axis, degrees )
lead: A rotation in 3D. first * second rotates by second first, then by first. quaternion:rotate(vector) turns a vec3; vector:rotate(quaternion) does the same.
arg: x, y, z, w | number | The four parts.
returns: quaternion
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
lib.quaternion = setmetatable({}, {
	__call = function(_, ...)
		local first = ...
		if is(QUAT, first) and select("#", ...) == 1 then return first end
		-- vanilla's axis-angle form; only a plain table (another value has no angle, and reading one would be an error)
		if I.type_name(first) == "table" and first.angle ~= nil then
			if select("#", ...) > 1 then error("quaternion: give either a table or numbers, not both", 0) end
			for key in pairs(first) do
				if key ~= "angle" and key ~= "axis" then error("quaternion: unknown field " .. tostring(key), 0) end
			end
			return q_methods.from_axis_angle(first.axis, first.angle)
		end
		return new_q(parts("quaternion", { "x", "y", "z", "w" }, number, ...))
	end,
	__index = q_methods,
	__newindex = function() error("quaternion can't be changed", 0) end,
	__metatable = false,
})
QUAT.__tostring = function(q)
	return number_text(q[1]) .. " " .. number_text(q[2]) .. " " .. number_text(q[3]) .. " " .. number_text(q[4])
end
-- Hamilton product: a * b rotates by b first, then by a (JOML's mul, which vanilla uses).
QUAT.__mul = function(a, b)
	need(QUAT, a, "quaternion *", "two quaternions"); need(QUAT, b, "quaternion *", "two quaternions")
	local ax, ay, az, aw = a[1], a[2], a[3], a[4]
	local bx, by, bz, bw = b[1], b[2], b[3], b[4]
	return new_q(aw * bx + ax * bw + ay * bz - az * by, aw * by - ax * bz + ay * bw + az * bx,
		aw * bz + ax * by - ay * bx + az * bw, aw * bw - ax * bx - ay * by - az * bz)
end
function q_methods.rotate(q, v)
	need(QUAT, q, "quaternion:rotate", "a quaternion (use : not .)"); need(VEC, v, "quaternion:rotate", "a vec3")
	local qx, qy, qz, qw = q[1], q[2], q[3], q[4]
	local n = qx * qx + qy * qy + qz * qz + qw * qw
	if n == 0 then error("quaternion:rotate: a zero quaternion can't rotate", 0) end
	-- v' = q v q^-1 (q is normalized here, so a non-unit quaternion rotates without scaling)
	local s = 1 / n
	local vx, vy, vz = v[1], v[2], v[3]
	local tx = 2 * (qy * vz - qz * vy)
	local ty = 2 * (qz * vx - qx * vz)
	local tz = 2 * (qx * vy - qy * vx)
	return new_vec(vx + s * (qw * tx + qy * tz - qz * ty), vy + s * (qw * ty + qz * tx - qx * tz),
		vz + s * (qw * tz + qx * ty - qy * tx), "quaternion:rotate")
end
function q_methods.from_axis_angle(axis, degrees)
	need(VEC, axis, "quaternion.from_axis_angle", "an axis vec3")
	degrees = scalar(degrees, "quaternion.from_axis_angle")
	local len = sqrt(axis[1] * axis[1] + axis[2] * axis[2] + axis[3] * axis[3])
	if len == 0 then error("quaternion.from_axis_angle: the axis can't be zero", 0) end
	local half = degrees * RAD / 2
	local s = sin(half) / len
	return new_q(axis[1] * s, axis[2] * s, axis[3] * s, cos(half))
end
-- The rotation that turns the forward direction (south, +z) to where yaw_pitch looks: yaw about -y, then pitch.
function q_methods.from_yaw_pitch(r)
	need(YP, r, "quaternion.from_yaw_pitch", "a yaw_pitch")
	local yaw = q_methods.from_axis_angle(new_vec(0, -1, 0), r[1])
	local pitch = q_methods.from_axis_angle(new_vec(1, 0, 0), r[2])
	return yaw * pitch
end

-- ---------------------------------------------------------------------------------------------------------------
-- aabb and bounding_box
-- ---------------------------------------------------------------------------------------------------------------

local aabb_methods, bb_methods = {}, {}
local AABB = value_type("aabb", { "min", "max" }, aabb_methods)
local BB = value_type("bounding_box", { "min", "max" }, bb_methods)

local min, max = math.min, math.max
-- Like vanilla's constructors: the corners are sorted, so any two opposite corners work.
local function new_aabb(a, b)
	return setmetatable({ new_vec(min(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])),
		new_vec(max(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])) }, AABB)
end
I.new_aabb = new_aabb
local function new_bb(a, b)
	return setmetatable({ new_block(min(a[1], b[1]), min(a[2], b[2]), min(a[3], b[3])),
		new_block(max(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3])) }, BB)
end

local function corners(what, meta, kind, first, second, n)
	if I.type_name(first) == "table" and second == nil and n == 1 then -- the table form: plain tables only
		local a, b = first.min or first[1], first.max or first[2]
		for key in pairs(first) do
			if key ~= "min" and key ~= "max" and key ~= 1 and key ~= 2 then
				error(what .. ": unknown field " .. tostring(key), 0)
			end
		end
		first, second = a, b
	elseif n ~= 2 then
		error(what .. " needs two corners (" .. kind .. "), got " .. n .. (n == 1 and " value" or " values"), 0)
	end
	if not is(meta, first) or not is(meta, second) then
		error(what .. " needs two " .. kind .. " corners, got " .. I.type_name(first) .. " and " .. I.type_name(second), 0)
	end
	return first, second
end

--[[doc
name: aabb( )
kind: reading
group: Values
words: box area region hitbox
syntax: aabb( min, max ) → aabb
lead: A box between two vec3 corners; a point exactly on the max side is outside (vanilla AABB). == :contains(vec3) :intersects(aabb) :grow(amount) :move(vec3) :center() :size().
arg: min, max | vec3 | The corners.
returns: aabb
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.aabb(...)
	local first, second = ...
	if is(AABB, first) and select("#", ...) == 1 then return first end
	return new_aabb(corners("aabb", VEC, "vec3", first, second, select("#", ...)))
end
--[[doc
name: bounding_box( )
kind: reading
group: Values
words: box area region cuboid
syntax: bounding_box( min, max ) → bounding_box
lead: A box between two block_pos corners, both sides included (vanilla BoundingBox). == :contains(block_pos) :intersects(bounding_box) :center().
arg: min, max | block_pos | The corners.
returns: bounding_box
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game
status: unconfirmed
]]
function lib.bounding_box(...)
	local first, second = ...
	if is(BB, first) and select("#", ...) == 1 then return first end
	return new_bb(corners("bounding_box", BLOCK, "block_pos", first, second, select("#", ...)))
end

AABB.__tostring = function(b) return tostring(b[1]) .. " to " .. tostring(b[2]) end -- FORMAT
BB.__tostring = AABB.__tostring

-- Vanilla's AABB.contains: the max side is open (a point on the max face is outside).
function aabb_methods.contains(b, v)
	need(AABB, b, "aabb:contains", "an aabb (use : not .)"); need(VEC, v, "aabb:contains", "a vec3")
	local lo, hi = b[1], b[2]
	return v[1] >= lo[1] and v[1] < hi[1] and v[2] >= lo[2] and v[2] < hi[2] and v[3] >= lo[3] and v[3] < hi[3]
end
-- Vanilla's AABB.intersects: touching faces do not count.
function aabb_methods.intersects(b, o)
	need(AABB, b, "aabb:intersects", "an aabb (use : not .)"); need(AABB, o, "aabb:intersects", "an aabb")
	local a1, a2, b1, b2 = b[1], b[2], o[1], o[2]
	return a1[1] < b2[1] and a2[1] > b1[1] and a1[2] < b2[2] and a2[2] > b1[2] and a1[3] < b2[3] and a2[3] > b1[3]
end
-- Vanilla's inflate: grows (or with a negative number shrinks) every side; corners are sorted again.
function aabb_methods.grow(b, n)
	need(AABB, b, "aabb:grow", "an aabb (use : not .)")
	n = scalar(n, "aabb:grow")
	local lo, hi = b[1], b[2]
	return new_aabb(new_vec(lo[1] - n, lo[2] - n, lo[3] - n, "aabb:grow"), new_vec(hi[1] + n, hi[2] + n, hi[3] + n, "aabb:grow"))
end
function aabb_methods.move(b, v)
	need(AABB, b, "aabb:move", "an aabb (use : not .)"); need(VEC, v, "aabb:move", "a vec3")
	return setmetatable({ b[1] + v, b[2] + v }, AABB)
end
function aabb_methods.center(b)
	need(AABB, b, "aabb:center", "an aabb (use : not .)")
	local lo, hi = b[1], b[2]
	return new_vec((lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2, (lo[3] + hi[3]) / 2)
end
function aabb_methods.size(b)
	need(AABB, b, "aabb:size", "an aabb (use : not .)")
	return b[2] - b[1]
end

-- Vanilla's BoundingBox.isInside: both sides included.
function bb_methods.contains(b, p)
	need(BB, b, "bounding_box:contains", "a bounding_box (use : not .)")
	need(BLOCK, p, "bounding_box:contains", "a block_pos")
	local lo, hi = b[1], b[2]
	return p[1] >= lo[1] and p[1] <= hi[1] and p[2] >= lo[2] and p[2] <= hi[2] and p[3] >= lo[3] and p[3] <= hi[3]
end
function bb_methods.intersects(b, o)
	need(BB, b, "bounding_box:intersects", "a bounding_box (use : not .)")
	need(BB, o, "bounding_box:intersects", "a bounding_box")
	local a1, a2, b1, b2 = b[1], b[2], o[1], o[2]
	return a1[1] <= b2[1] and a2[1] >= b1[1] and a1[2] <= b2[2] and a2[2] >= b1[2] and a1[3] <= b2[3] and a2[3] >= b1[3]
end
-- Vanilla's getCenter: the middle block, rounded down.
function bb_methods.center(b)
	need(BB, b, "bounding_box:center", "a bounding_box (use : not .)")
	local lo, hi = b[1], b[2]
	return new_block(floor((lo[1] + hi[1]) / 2), floor((lo[2] + hi[2]) / 2), floor((lo[3] + hi[3]) / 2))
end

-- ---------------------------------------------------------------------------------------------------------------
-- global_pos and location
-- ---------------------------------------------------------------------------------------------------------------

local gp_methods, loc_methods, loc_computed = {}, {}, {}
local GP = value_type("global_pos", { "dimension", "pos" }, gp_methods)
local LOC = value_type("location", { "world", "pos", "rot" }, loc_methods, loc_computed)
I.LOCATION_META = LOC

--[[doc
name: global_pos( )
kind: reading
group: Values
words: dimension position
syntax: global_pos( dimension, pos ) → global_pos
lead: A block position in a dimension (vanilla GlobalPos). Also takes { dimension = , pos = }.
arg: dimension | text | An id like "overworld".
arg: pos | block_pos | The position.
returns: global_pos
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; the dimension id is checked by one Java call
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.global_pos(...)
	local first, second = ...
	local n = select("#", ...)
	if is(GP, first) and n == 1 then return first end
	if I.type_name(first) == "table" and n == 1 then -- the table form: plain tables only
		for key in pairs(first) do
			if key ~= "dimension" and key ~= "pos" then error("global_pos: unknown field " .. tostring(key), 0) end
		end
		first, second, n = first.dimension, first.pos, 2
	end
	if n ~= 2 then error("global_pos needs a dimension and a block_pos", 0) end
	if type(first) ~= "string" then error("global_pos: dimension must be an id like \"overworld\", got " .. I.type_name(first), 0) end
	local id = J.dimension_id(first)
	if not id then error("global_pos: \"" .. first .. "\" is not a valid id", 0) end
	need(BLOCK, second, "global_pos", "a block_pos")
	return setmetatable({ id, second }, GP)
end
GP.__tostring = function(g) return tostring(g[2]) .. " in " .. g[1] end -- FORMAT

local ZERO_ROT = new_yp(0, 0)
function I.new_location(world, pos, rot) return setmetatable({ world, pos, rot or ZERO_ROT }, LOC) end

--[[doc
name: location( )
kind: reading
group: Values
words: where place spot
syntax: location( world, pos, rot ) → location
lead: A world, an exact position and a direction (yaw_pitch optional: 0, 0). Fields: l.world, l.pos, l.rot, and l.block (the block object it is in; nil while that chunk isn't loaded, and it never loads one). == :chunk() :offset(vec3). Also takes { world = , pos = , rot = }.
arg: world | world | The world.
arg: pos | vec3 | The exact position.
arg: rot? | yaw_pitch | The direction. Optional: 0, 0.
returns: location
expert: Thread | main thread, like every script
expert: Cost | 0.1–1 µs to make one from numbers, 1–5 µs from command text (guess, not measured); the math on it is plain Lua
expert: Under the hood | a plain Lua table with a hidden shared metatable; no Java, except l.block (one call: is the chunk loaded)
expert: Can fail | an error naming the field when a value is not a number, is NaN or infinity, or (whole-number types) not whole or outside Java's int range
expert: Reload | values are plain Lua: they go with the Lua state on /lullaby reload
expert: Time limit | not checked: plain Lua, no call into the game (l.block is checked: it calls into the game)
status: unconfirmed
]]
function lib.location(...)
	local world, pos, rot = ...
	local n = select("#", ...)
	if is(LOC, world) and n == 1 then return world end
	if I.type_name(world) == "table" and n == 1 then -- the table form: plain tables only
		for key in pairs(world) do
			if key ~= "world" and key ~= "pos" and key ~= "rot" then error("location: unknown field " .. tostring(key), 0) end
		end
		world, pos, rot, n = world.world, world.pos, world.rot, 3
	end
	if n < 2 or n > 3 then error("location needs a world, a vec3 and maybe a yaw_pitch", 0) end
	if I.kind_of[world] ~= "world" then error("location: world must be a world, got " .. I.type_name(world), 0) end
	need(VEC, pos, "location", "a vec3 position")
	if rot ~= nil then need(YP, rot, "location", "a yaw_pitch direction") end
	return I.new_location(world, pos, rot)
end
LOC.__tostring = function(l) return tostring(l[2]) .. " in " .. tostring(l[1]) end -- FORMAT

-- l.block: the block object the position is in; nil while its chunk (or its world) isn't loaded. Never loads one.
function loc_computed.block(l)
	local p = l[2]:block_pos()
	if not J.chunk_loaded(I.slot_of[l[1]], floor(p[1] / 16), floor(p[3] / 16)) then return nil end
	return I.block_at(tostring(l[1]), p[1], p[2], p[3])
end
-- The chunk object the position is in (vanilla: block coordinate >> 4).
function loc_methods.chunk(l)
	need(LOC, l, "location:chunk", "a location (use : not .)")
	local p = l[2]
	return I.chunk_at(tostring(l[1]), floor(p[1] / 16), floor(p[3] / 16))
end
function loc_methods.offset(l, v)
	need(LOC, l, "location:offset", "a location (use : not .)"); need(VEC, v, "location:offset", "a vec3")
	return setmetatable({ l[1], l[2] + v, l[3] }, LOC)
end
