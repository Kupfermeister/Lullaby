-- Lullaby library: script commands.
--
--   command{ "name", function(c) ... end, args = { ... }, branches = { ... }, permission = "x", show = function(p),
--            aliases = { "n" } }
-- A command is a step. A step has an args line (values typed one after the other) and may split at the end of that
-- line into branches: a key like `buy = { ... }` is a word to type, an unnamed entry `{ args = { ... }, ... }` is a
-- step whose line starts with a value (e.g. /tp <player> next to /tp <x> <y> <z>). Every branch is a step again:
--   { function(c) ... end, args = { ... }, branches = { ... }, permission = "x", show = function(p), aliases = { } }
-- (aliases only for word steps). The function runs when the line is typed to its end; a step without a function
-- only leads on to its branches.
-- An args entry: { name = "amount", type = "integer", min = 1, max = 64, suggest = { ... } or function(p),
--   check = function(value, p) return nil (ok) or "why not" end, optional = true }
-- Types: word (default), text (the rest of the line, last only), integer, number, bool, player (an online player).
-- Optional values only at the very end of a line with no branches after it.
-- The function gets c: c.player (nil from the console), c.args.<name> (every value of the path; players as player
-- objects). A rejected check shows its text in red and the function does not run.
--
-- What a player sees (permission, show, suggest functions) is worked out when their command tree is built: on join,
-- after /lullaby reload and with refresh_commands{ player = p }. Java keeps the result (Paper builds trees on other
-- threads, which must never call Lua; see ScriptCommands.java). check runs when the command runs.
--   refresh_commands{ player = p }   resends p's commands, with permission, show and suggest worked out again
--   online_players()                 list of every online player
local J, lib, I = ...

local type, error, pairs, ipairs, tostring, select = type, error, pairs, ipairs, tostring, select
local concat, sort = table.concat, table.sort
local debug_getinfo = debug.getinfo -- private copy: the sandbox removes debug from scripts' reach

local TYPES = { word = true, text = true, integer = true, number = true, bool = true, player = true }
local STEP_FIELDS = { args = true, branches = true, permission = true, show = true, aliases = true }
local ARG_FIELDS = { name = true, type = true, min = true, max = true, suggest = true, check = true, optional = true }
local US, RS = "\31", "\30" -- separators of the snapshot text (see cmd_refresh in JavaFunctions.java)

-- Filled while scripts load (one Lua state per load, so a reload starts empty):
local heads = {}     -- in build order (parents first): { id, parent = head id or nil, permission, show, origin }
local suggesters = {} -- { id, head = head id, fn, origin }: suggest functions, run per player on refresh
local runners = {}   -- executable node id -> { fn, checks = { { name, fn } }, origin }

-- ---------------------------------------------------------------------------------------------------------------
-- Checking a declaration (all of it first, so a mistake never leaves half a command in the game)
-- ---------------------------------------------------------------------------------------------------------------

-- Every check below raises its message without a position (level 0); lib.command adds the script line.
local function fail(message)
	error("command{}: " .. message, 0)
end

-- A word to type: anything without spaces, not empty (Kupfermeister: symbols allowed, "/set" gives WorldEdit-style //set).
local function check_word(word, what)
	if type(word) ~= "string" or word == "" or word:find("%s") then
		fail(what .. " must be a word without spaces, got "
			.. (type(word) == "string" and "\"" .. word .. "\"" or I.type_name(word)))
	end
end

local function check_fields(t, allowed, what)
	for key in pairs(t) do
		if type(key) ~= "number" and not allowed[key] then fail(what .. " has no field \"" .. tostring(key) .. "\"") end
	end
end

-- Checks one args line. `names` holds the value names of the path so far: they must be unique (c.args.<name>).
-- Returns whether the line ends in an optional or text value (then no branches may follow).
local function check_line(args, where, names, value_step)
	if args == nil then return false end
	if type(args) ~= "table" then fail("args of " .. where .. " must be a list { { name = ... } }") end
	local closed, optional = false, false
	for i = 1, #args do
		local a = args[i]
		if type(a) ~= "table" then fail("value " .. i .. " of " .. where .. " must be { name = ..., type = ... }") end
		check_fields(a, ARG_FIELDS, "value " .. i .. " of " .. where)
		check_word(a.name, "the name of value " .. i .. " of " .. where)
		local at = where .. " <" .. a.name .. ">"
		if names[a.name] then -- names[x] is where the first one is, e.g. "/team <name> (value 1)"
			fail("two values are called \"" .. a.name .. "\" on one path (" .. names[a.name] .. " and " .. at
				.. " (value " .. i .. ")), but c.args." .. a.name .. " can hold only one")
		end
		names[a.name] = at .. " (value " .. i .. ")"
		local t = a.type or "word"
		if not TYPES[t] then
			fail(at .. " has unknown type \"" .. tostring(t) .. "\" (word, text, integer, number, bool, player)")
		end
		if closed then fail(at .. " comes after a text value, which takes the rest of the line") end
		if (a.min ~= nil or a.max ~= nil) and t ~= "integer" and t ~= "number" then
			fail(at .. ": min and max are only for integer and number")
		end
		if a.min ~= nil and type(a.min) ~= "number" then fail(at .. ": min must be a number") end
		if a.max ~= nil and type(a.max) ~= "number" then fail(at .. ": max must be a number") end
		if a.min and a.max and a.min > a.max then fail(at .. ": min is bigger than max") end
		if t == "integer" and (a.min and a.min % 1 ~= 0 or a.max and a.max % 1 ~= 0) then
			fail(at .. ": min and max of an integer must be whole numbers")
		end
		if a.suggest ~= nil and type(a.suggest) ~= "function" and type(a.suggest) ~= "table" then
			fail(at .. ": suggest must be a list { \"a\", \"b\" } or function(p)")
		end
		if a.check ~= nil and type(a.check) ~= "function" then fail(at .. ": check must be function(value, p)") end
		if a.optional then
			if i == 1 and value_step then fail(at .. ": the first value of a value branch can't be optional") end
			optional = true
		elseif optional then
			fail(at .. " comes after an optional value (optional values only at the end)")
		end
		if t == "text" then closed = true end
	end
	return closed or optional
end

local check_step

-- Words and value names at one split must differ: Minecraft would silently glue equal ones together.
local function check_branches(branches, where, names, ends_open)
	if branches == nil then return end
	if type(branches) ~= "table" then fail("branches of " .. where .. " must be { word = { ... }, { args = ... } }") end
	if ends_open then fail(where .. " can't split into branches after an optional or text value") end
	local seen = {}
	local function claim(name)
		if seen[name] then fail(where .. " has two branches called \"" .. name .. "\"") end
		seen[name] = true
	end
	for key, branch in pairs(branches) do
		if type(key) == "string" then
			check_word(key, "a branch word of " .. where)
			claim(key)
			check_step(branch, where .. " " .. key, names, false, 1)
			for _, alias in ipairs(branch.aliases or {}) do
				check_word(alias, "an alias of " .. where .. " " .. key)
				claim(alias)
			end
		elseif type(key) ~= "number" then
			fail("branches of " .. where .. " must be words or unnamed { args = ... } steps")
		end
	end
	for i = 1, #branches do
		local branch = branches[i]
		local first = type(branch) == "table" and type(branch.args) == "table" and branch.args[1]
		if type(first) ~= "table" then
			fail("unnamed branch " .. i .. " of " .. where .. " must start with a value: { args = { { name = ... } } }")
		end
		if branch.aliases ~= nil then fail("unnamed branch " .. i .. " of " .. where .. " can't have aliases") end
		check_step(branch, where, names, true, 1)
		claim(first.name) -- a valid word by now
	end
end

-- Checks a step and everything below it. `names`: value names of the path so far (copied: branches may reuse
-- names), `fn_index`: where the function is (2 for the command, after its name; 1 in a branch).
function check_step(step, where, names, value_step, fn_index)
	if type(step) ~= "table" then fail(where .. " must be { function(c) ... end, ... }") end
	check_fields(step, STEP_FIELDS, where)
	local fn = step[fn_index]
	if fn ~= nil and type(fn) ~= "function" then
		fail("the function of " .. where .. " must be a function, got " .. I.type_name(fn))
	end
	if step.permission ~= nil and type(step.permission) ~= "string" then
		fail("permission of " .. where .. " must be text")
	end
	if step.show ~= nil and type(step.show) ~= "function" then fail("show of " .. where .. " must be function(p)") end
	if step.aliases ~= nil and type(step.aliases) ~= "table" then
		fail("aliases of " .. where .. " must be a list { \"a\" }")
	end
	if fn == nil and step.branches == nil then fail(where .. " needs a function or branches") end
	local path = {}
	for k, v in pairs(names) do path[k] = v end
	local ends_open = check_line(step.args, where, path, value_step)
	check_branches(step.branches, where, path, ends_open)
end

-- The whole declaration; returns the words the command takes at the top (name and aliases).
local function check_command(a)
	local name = a[1]
	check_word(name, "the command name (first value)")
	check_step(a, "/" .. name, {}, false, 2)
	local taken, seen = { name }, { [name] = true }
	for _, alias in ipairs(a.aliases or {}) do
		check_word(alias, "an alias of /" .. name)
		if seen[alias] then fail("/" .. name .. " has \"" .. alias .. "\" twice") end
		seen[alias] = true
		taken[#taken + 1] = alias
	end
	for _, word in ipairs(taken) do
		if J.cmd_exists(word) then fail("/" .. word .. " already exists") end
	end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Building (only after the whole declaration checked out)
-- ---------------------------------------------------------------------------------------------------------------

local function suggest_list(s)
	if type(s) ~= "table" then return "" end
	local parts = {}
	for i = 1, #s do parts[i] = (tostring(s[i]):gsub("[\n\30\31]", " ")) end
	return concat(parts, "\n")
end

local build_step

-- Builds one step below node `parent` (-1 for the command itself). `head_parent`: the id of the nearest visible
-- head above (nil at the top), `checks`: the checks of the path so far.
function build_step(step, word, fn, parent, head_parent, checks, origin)
	local args = step.args or {}
	local executable_from -- index in the line from which nodes run the function (0 = the word node)
	if fn then
		executable_from = #args
		for i = 1, #args do
			if args[i].optional then executable_from = i - 1 break end
		end
	end
	local my_checks = {}
	for i = 1, #checks do my_checks[i] = checks[i] end

	local node, head = parent, nil
	local function run_here(id)
		runners[id] = { fn = fn, checks = my_checks, origin = origin }
	end
	local function head_info(id)
		heads[#heads + 1] = { id = id, parent = head_parent, permission = step.permission, show = step.show,
			origin = origin }
		head = id
	end

	if word then
		node = J.cmd_node(parent, false, word, "", 0, 0, false, "", executable_from == 0, true, step.permission or "")
		head_info(node)
		if executable_from == 0 then run_here(node) end
	end
	for i = 1, #args do
		local a = args[i]
		local t = a.type or "word"
		local executable = executable_from ~= nil and i >= executable_from
		local is_head = not word and i == 1
		node = J.cmd_node(node, true, a.name, t, a.min or -math.huge, a.max or math.huge, a.suggest ~= nil,
			suggest_list(a.suggest), executable, is_head, is_head and step.permission or "")
		if is_head then head_info(node) end
		if type(a.suggest) == "function" then
			suggesters[#suggesters + 1] = { id = node, head = head, fn = a.suggest, origin = origin }
		end
		if a.check then my_checks[#my_checks + 1] = { name = a.name, fn = a.check } end
		if executable then run_here(node) end
	end

	local branches = step.branches
	if branches then
		-- words in a fixed order, so node ids (and the tree) come out the same on every load
		local words = {}
		for key in pairs(branches) do
			if type(key) == "string" then words[#words + 1] = key end
		end
		sort(words)
		for _, key in ipairs(words) do
			local b = branches[key]
			build_step(b, key, b[1], node, head, my_checks, origin)
		end
		for i = 1, #branches do
			local b = branches[i]
			build_step(b, nil, b[1], node, head, my_checks, origin)
		end
	end
	if word and step.aliases then
		for _, alias in ipairs(step.aliases) do J.cmd_alias(head, alias) end
	end
	return head
end

--[[doc
name: command{ }
kind: doing
group: Commands
words: slash brigadier cmd argument
syntax: command{ "name", function(c) ... end, args = { ... }, branches = { ... }, permission = text, show = function(p), aliases = { ... } }
lead: Adds a script command. Its args are values typed one after the other; at the end it may split into branches: a key like buy = { ... } is a word to type, an unnamed { args = { ... } } starts with a value. Every branch takes the same fields again. The function gets c: c.player (nil from the console) and c.args.<name>.
arg: 1 | text | The command name, without /.
arg: 2? | function | Runs when the line is typed to its end. Without it, the command only leads to its branches.
arg: args? | list of table | Each: { name = text, type = "word" | "text" | "integer" | "number" | "bool" | "player", min, max, suggest = list or function(p), check = function(value, p), optional = true }. text takes the rest of the line (last only); optional values only at the very end.
arg: branches? | table | Words and value steps that follow.
arg: permission? | text | Needed permission.
arg: show? | function | function(p) returns true if p may see it.
arg: aliases? | list of text | Other names (word steps only).
returns: nothing
note: Works only while scripts load (at the top of a file). permission, show and suggest are worked out on join, after /lullaby reload and with refresh_commands{ }; check runs when the command runs and its text is shown in red.
example:
  command{ "heal", function(c)
    message{ player = c.player, text = "Healed " .. c.args.amount }
  end, args = { { name = "amount", type = "integer", min = 1, max = 20 } } }
expert: Thread | main thread, like every script
expert: Cost | registering: 20–500 µs per command at load; running one: 5–30 µs plus the function (guess, not measured)
expert: Under the hood | vanilla Brigadier nodes (ScriptCommands.java); each player is sent what they may see
expert: Can fail | at load: every field is checked and named in the error; a /name that already exists; used after loading
expert: Reload | every script command is removed and players get the new list
expert: Time limit | the function runs as a task; checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.command(a)
	if type(a) ~= "table" then error("command{} needs { }: command{ \"name\", function(c) ... end }", 2) end
	-- players' trees are worked out after loading, so a later command would be half there
	if not I.loading then error("command{} works only while scripts load (at the top of a file)", 2) end
	local ok, err = pcall(check_command, a)
	if not ok then error(err, 2) end
	J.cmd_register(build_step(a, a[1], a[2], -1, nil, {}, "/" .. a[1]))
end

-- ---------------------------------------------------------------------------------------------------------------
-- Per player: what they see (main thread, on join, reload and refresh_commands)
-- ---------------------------------------------------------------------------------------------------------------

function I.refresh_player(uuid)
	local p = I.player_by_uuid(uuid)
	local visible, ids = {}, {}
	for _, h in ipairs(heads) do
		local see = h.parent == nil or visible[h.parent]
		if see and h.permission then see = J.has_permission(I.slot_of[p], h.permission) end
		if see and h.show then
			local ok, result = I.protected("show (" .. h.origin .. ")", h.show, p)
			see = ok and result and true or false
		end
		if see then
			visible[h.id] = true
			ids[#ids + 1] = h.id
		end
	end
	local records = {}
	for _, s in ipairs(suggesters) do
		if visible[s.head] then
			local ok, list = I.protected("suggest (" .. s.origin .. ")", s.fn, p)
			if ok and type(list) == "table" then
				local parts = { s.id }
				for i = 1, #list do parts[#parts + 1] = (tostring(list[i]):gsub("[\30\31]", " ")) end
				records[#records + 1] = concat(parts, US)
			elseif ok then
				local info = debug_getinfo(s.fn, "S")
				I.report("warning", nil, 1, "suggest (" .. info.short_src .. " line " .. info.linedefined
					.. ") must return a list { \"a\", \"b\" }, got " .. I.type_name(list), "suggest (" .. s.origin .. ")")
			end
		end
	end
	J.cmd_refresh(uuid, concat(ids, "\n"), concat(records, RS))
end

local REFRESH_TAKES = I.takes(0, "player")
--[[doc
name: refresh_commands{ }
kind: doing
group: Commands
words: update resend tree
syntax: refresh_commands{ player = player }
lead: Checks again which script commands the player can see (permission, show, suggest) and sends them.
arg: player | player | The player.
returns: nothing
expert: Thread | main thread, like every script
expert: Cost | 50 µs–2 ms per player, more with many show and suggest functions (guess, not measured)
expert: Under the hood | runs every permission, show and suggest for the player, then sends vanilla's Commands packet
expert: Can fail | an unknown field (the error lists the ones it takes), player not a player; an offline player is skipped quietly; errors in show or suggest are reported and that part is hidden
expert: Reload | reload refreshes every online player anyway
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.refresh_commands(a)
	if type(a) ~= "table" then error("refresh_commands{} needs { }: refresh_commands{ player = p }", 2) end
	if I.object_kind(a.player) ~= "player" then
		error("refresh_commands{}: player must be a player, got " .. I.type_name(a.player), 2)
	end
	I.only(a, "refresh_commands{}", REFRESH_TAKES, 2)
	if a.player.online then I.refresh_player(a.player.uuid) end
end

--[[doc
name: online_players( )
kind: reading
group: Players
words: list everyone all
syntax: online_players( ) → { player, ... }
lead: Every player online now, as a list.
returns: list of player
expert: Thread | main thread, like every script
expert: Cost | 2–10 µs plus 0.3–2 µs per player (guess, not measured)
expert: Under the hood | one Java call; known players reuse their object
expert: Can fail | never
expert: Reload | player objects are made new after reload
expert: Time limit | checked: it calls into the game, so a script over script_time_limit stops here with the normal red error
status: unconfirmed
]]
function lib.online_players()
	local list = {}
	for uuid in J.online_players():gmatch("[^\n]+") do list[#list + 1] = I.player_by_uuid(uuid) end
	return list
end

-- ---------------------------------------------------------------------------------------------------------------
-- Running (Java calls this when a script command is typed)
-- ---------------------------------------------------------------------------------------------------------------

-- Runs as the command's task: checks first, then the function.
local function run(r, c)
	for _, check in ipairs(r.checks) do
		local value = c.args[check.name]
		if value ~= nil then
			local answer = check.fn(value, c.player)
			if type(answer) == "string" then
				J.cmd_reject(answer)
				return
			elseif answer ~= nil then
				local info = debug_getinfo(check.fn, "S")
				error("check of <" .. check.name .. "> (" .. info.short_src .. " line " .. info.linedefined
					.. ") must return nothing (ok) or a text (why not), got " .. I.type_name(answer), 0)
			end
		end
	end
	r.fn(c)
end

function I.run_command(id, uuid, ...)
	local r = runners[id]
	if not r then return end
	local args = {}
	for i = 1, select("#", ...), 3 do
		local name, kind, value = select(i, ...)
		args[name] = kind == "player" and I.player_by_uuid(value) or value
	end
	local c = { player = uuid and I.player_by_uuid(uuid) or nil, args = args }
	I.start_task(run, { n = 2, r, c }, r.origin)
end
