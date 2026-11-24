-- Lullaby library: loading script files and import.
--
-- Every .lua file under plugins/lullaby/scripts loads once, in alphabetical path order, except files and folders
-- whose name starts with "--" (standby). Each file has its own globals: a name it sets without `local` is visible in
-- that file only, and the library is reachable behind it. Other files are used with import:
--   local x = import("path")   "/" between folders, "../" one up, a leading "/" from the scripts folder, no ".lua"
-- import returns what the file returns; a file not loaded yet loads right then, and no file ever loads twice.
-- The top of a file is not a task (wait errors there); its functions run later from events and run{}.
local J, lib, I = ...

local loadstring, setfenv, setmetatable, type, error = loadstring, setfenv, setmetatable, type, error
local concat = table.concat

local exists = {} -- loadable paths ("lobby/join.lua") -> true
local files = {}  -- path -> { done = bool, result = what the file returned }
local count = 0

-- "../shared/util" seen from "lobby/join.lua" -> "shared/util.lua"
local function resolve(from, path)
	local parts = {}
	if path:sub(1, 1) ~= "/" then
		for folder in from:gmatch("([^/]+)/") do parts[#parts + 1] = folder end
	end
	for part in path:gmatch("[^/]+") do
		if part == ".." then
			if #parts == 0 then return nil end
			parts[#parts] = nil
		elseif part ~= "." then
			parts[#parts + 1] = part
		end
	end
	return concat(parts, "/") .. ".lua"
end

local load_file

local function import_from(from, path)
	if type(path) ~= "string" then error("import: needs a path text like \"shared/util\", got " .. I.type_name(path), 3) end
	local target = resolve(from, path)
	if not target or not exists[target] then
		error("import: scripts/" .. (target or path) .. " does not exist (or is on standby)", 3)
	end
	local file = files[target]
	if file and not file.done then error("import: scripts/" .. target .. " imports itself in a circle", 3) end
	return load_file(target)
end

function load_file(path)
	local file = files[path]
	if file then return file.result end
	file = { done = false }
	files[path] = file
	local source = J.read_script(path)
	local chunk, err = loadstring(source, "@scripts/" .. path)
	if not chunk then -- a syntax error: the whole file is skipped
		J.report("error", "scripts/" .. path, tonumber(err:match(":(%d+): ")) or -1, "loading",
			(err:gsub("^[^\n]-:%d+: ", "", 1)))
		file.done = true
		return nil
	end
	local env = setmetatable({}, { __index = lib, __metatable = false })
	env._G = env
	I.std_copies(env) -- its own string, table, math, coroutine and bit (core.lua)
	env.import = function(p) return import_from(path, p) end
	setfenv(chunk, env)
	local ok, result = I.protected("loading", chunk)
	file.done = true
	if ok then file.result = result end
	count = count + 1
	return file.result
end

-- Called by Java: loads every script, returns how many loaded.
function I.load_all()
	local order = {}
	for path in J.list_scripts():gmatch("[^\n]+") do
		exists[path] = true
		order[#order + 1] = path
	end
	I.loading = true -- command{} works only now (see commands.lua)
	for i = 1, #order do load_file(order[i]) end
	I.loading = false
	return count
end

-- Called by Java for /lullaby run (LullabyCommand.java): one line of Lua, started at once as a task (so it may wait,
-- listen, start tasks: anything a handler may), with its own globals like a script file plus `me`. Only command{}
-- refuses, as anywhere after loading. Errors are normal reports, with "/lullaby run by <name>" as the via line, since
-- the code has no file. When the line ends without an error, the sender gets "Done" (a player in chat, the console
-- in the log; a command block nothing). A command block's line runs only with unlock.command_blocks.
-- uuid: me (nil: none); sender: the sending player's uuid (nil: console or command block).
function I.raw_run(code, uuid, sender, from_block, who)
	local origin = "/lullaby run by " .. who -- FORMAT: via line of /lullaby run
	if from_block and I.config.unlock.command_blocks ~= true then
		-- FORMAT: /lullaby run from a command block while it is locked
		J.report("error", "", -1, origin, "/lullaby run from command blocks is locked (unlock.command_blocks in "
			.. "plugins/lullaby/config.lua)")
		return
	end
	local chunk, err = loadstring(code, "@/lullaby run")
	if not chunk then
		J.report("error", "", -1, origin, (err:gsub("^[^\n]-:%d+: ", "", 1)))
		return
	end
	local env = setmetatable({}, { __index = lib, __metatable = false })
	env._G = env
	I.std_copies(env)
	env.me = uuid and I.player_by_uuid(uuid) or nil
	setfenv(chunk, env)
	I.start_task(function()
		chunk()
		-- FORMAT: /lullaby run ended without an error
		local to = sender and I.player_by_uuid(sender)
		if to then lib.message{ player = to, text = lib.component({ text = "Done", color = "green" }) }
		elseif not from_block then lib.print("/lullaby run by " .. who .. ": done") end
	end, { n = 0 }, origin)
end

--[[doc
id: lullaby_run
name: /lullaby run
kind: lua
group: Scripts
words: run execute exec eval command chat console line test try debug ex me
syntax: /lullaby run <lua code>
lead: For experts: runs one line of Lua right away as its own task, like an event handler, with me as the player who typed it (nil from the console; with /execute as <player>, that player). Everything works, wait too: /lullaby run modify_entity{ entity = me, glowing = true } wait("5s") modify_entity{ entity = me, glowing = false }. When the line ends without an error you get a green "Done"; it shows no values by itself: use message{ player = me, text = ... } or print( ).
note: Each line has its own globals, so nothing carries over to the next line or to the scripts. Only command{ } doesn't work (commands are made while scripts load). on{ }, after{ } and run{ } started here keep running until the next /lullaby reload. Errors are the normal red error, shown to every op and the console, with "via /lullaby run by <name>".
expert: Permission | lullaby.run (ops have it) and lullaby.command; every use is written to the console with who typed it
expert: Command blocks | refused unless unlock.command_blocks = true in the config: anyone who can edit a command block could run any code
expert: Time limit | the config's script_time_limit, like any task; a wait starts the clock again
expert: Reload | a line still waiting ends (like every task), and its handlers are removed; what it did in the game stays
]]

--[[doc
id: import
name: import( )
kind: reading
group: Scripts
words: require include module file load other
syntax: local x = import( "path" )
lead: Uses another script file and gives back what that file returns. "/" between folders, "../" goes one up, a leading "/" starts at the scripts folder; no ".lua". A file not loaded yet loads right then, and no file ever loads twice.
arg: path | text | Like "shared/util".
returns: what the file returns
note: Files and folders whose name starts with "--" are on standby: they don't load and can't be imported.
expert: Thread | main thread, like every script
expert: Cost | a file loaded already: 1–5 µs (it gives back the kept result); a new file: as loading it, guess 0.1–2 ms (not measured)
expert: Under the hood | plain Lua: the file's text comes from Java once, runs with its own globals, and its result is kept
expert: Can fail | a file that doesn't exist or is on standby, files importing each other in a circle; an error inside the imported file is reported for that file, and import gives nil
expert: Reload | everything loads again from the files; kept results go with the Lua state
expert: Time limit | each file's loading has its own clock; checked at calls into the game
status: unconfirmed
]]
