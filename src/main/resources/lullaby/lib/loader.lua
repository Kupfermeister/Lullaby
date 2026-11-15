-- Lullaby library, file 7 of 7: loading script files and import.
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
		J.report("error", "scripts/" .. path, tonumber(err:match(":(%d+): ")) or -1, "", "loading",
			(err:gsub("^[^\n]-:%d+: ", "", 1)))
		file.done = true
		return nil
	end
	local env = setmetatable({}, { __index = lib, __metatable = false })
	env._G = env
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
