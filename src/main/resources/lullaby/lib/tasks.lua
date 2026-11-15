-- Lullaby library: tasks (run, wait, stop) and the scheduler that resumes waiting tasks each tick.
--
-- A task is one running function inside its own coroutine. Event handlers run as tasks automatically.
--   run{ f, args = { ... }, final = g, id = x }   starts f(...) now; returns the task, or nil if a task of the same
--                                                 function (same place in the code) with the same id is running
--   wait(duration)                                pauses the current task: number = seconds, or "3t" "2s" "1m" "1h" "1d"
--   stop{ task = t }                              ends t and runs its final; force = true skips the final
-- final runs at the end in every case (finished, stopped, error, reload, server stop) except a force stop, and it
-- never waits (a wait in it is ignored with a warning), so it always completes.
-- Coroutines are reused from a pool, because creating one per event would cost time on busy events.
local J, lib, I = ...

local co_create, co_resume, co_yield = coroutine.create, coroutine.resume, coroutine.yield
local co_running, co_status = coroutine.running, coroutine.status
local debug_getinfo = debug.getinfo
local unpack, select, type, error, setmetatable = unpack, select, type, error, setmetatable
local ceil, max, maxn = math.ceil, math.max, table.maxn

local WAIT, DONE, STOP = {}, {}, {} -- unique markers passed between tasks and the scheduler
I.STOP = STOP -- core.lua's pcall guards pass it on
local DAY_MS = 86400000

local now = 0                                   -- ticks since this Lua state started
local buckets = {}                              -- tick -> list of tasks that wake up then
local co_task = setmetatable({}, { __mode = "k" }) -- coroutine -> the task running in it
local alive = {}                                -- task -> true while alive (strong, so finals can run on reload)
local handles = setmetatable({}, { __mode = "k" }) -- handle given to scripts -> task
local ids = {}                                  -- place of a function -> { id -> task }
local in_final = {}                             -- coroutine (or I.MAIN) -> how many finals run in it right now
local pool = {}                                 -- idle coroutines to reuse
local POOL_MAX = 64

-- The body of every task coroutine: runs one job, then offers itself for the next one.
local function worker(job)
	while true do
		job.f(unpack(job.args, 1, job.args.n))
		job = co_yield(DONE)
	end
end

-- What started the code running right now, for error reports (e.g. "PlayerJoinEvent (on)").
function I.current_origin()
	local co = co_running()
	local task = co and co_task[co]
	return task and task.origin or nil
end

local function run_final(task)
	local key = co_running() or I.MAIN
	in_final[key] = (in_final[key] or 0) + 1
	I.protected("final of " .. task.origin, task.final, unpack(task.args, 1, task.args.n)) -- FORMAT
	in_final[key] = in_final[key] - 1
end

local function finish(task, skip_final)
	task.alive = false
	alive[task] = nil
	if task.id_slot and task.id_slot[task.id] == task then task.id_slot[task.id] = nil end
	if task.final and not skip_final then run_final(task) end
end

local function schedule(task, ticks)
	local at = now + ticks
	task.wake = at
	local bucket = buckets[at]
	if not bucket then bucket = {}; buckets[at] = bucket end
	bucket[#bucket + 1] = task
end

-- Resumes a task and handles what it did: waited, finished, stopped itself or failed.
local function step(task, ...)
	local co = task.co
	local ok, what, ticks = co_resume(co, ...)
	if ok and what == WAIT then
		if task.pending_stop ~= nil then -- stopped while it was busy starting another task
			co_task[co] = nil
			finish(task, task.pending_stop)
		else
			schedule(task, ticks)
		end
		return
	end
	co_task[co] = nil
	if ok and what == DONE then
		if #pool < POOL_MAX then pool[#pool + 1] = co end
		finish(task, task.pending_stop == true)
	elseif not ok and what == STOP then -- the task stopped itself with stop{}
		finish(task, task.force_stop)
	elseif not ok then -- an error: the coroutine is dead (not reused), its stack still shows where it happened
		I.report("error", co, 0, what, task.origin)
		finish(task, task.pending_stop == true)
	else
		I.report("error", co, 0, "coroutine.yield can't be used in a task directly, use wait()", task.origin)
		finish(task, task.pending_stop == true)
	end
end

-- Starts f(args) as a task now (used by run{} and by event handlers).
function I.start_task(f, args, origin, final, id, id_slot)
	local task = { f = f, args = args, origin = origin, final = final, id = id, id_slot = id_slot, alive = true }
	alive[task] = true
	if id_slot then id_slot[id] = task end
	local co = pool[#pool]
	if co then pool[#pool] = nil else co = co_create(worker) end
	task.co = co
	co_task[co] = task
	step(task, task) -- the task table is also the job: worker reads job.f and job.args
	return task
end

-- Starts an event handler f(e) as a task; returns true if it is still running (it waited). A handler that ended
-- without waiting is held by nobody (handlers get no task handle), so its task table goes back to a pool: a busy
-- event makes no new tables here.
local task_pool = {}
function I.start_handler(f, e, origin)
	local n = #task_pool
	local task = task_pool[n]
	if task then task_pool[n] = nil else task = { args = { n = 1 } } end
	task.f, task.origin, task.alive = f, origin, true
	task.args[1] = e
	alive[task] = true
	local co = pool[#pool]
	if co then pool[#pool] = nil else co = co_create(worker) end
	task.co = co
	co_task[co] = task
	step(task, task)
	if task.alive then return true end
	task.f, task.origin, task.co, task.args[1] = nil, nil, nil, nil
	task.pending_stop, task.force_stop, task.wake = nil, nil, nil
	if n < POOL_MAX then task_pool[#task_pool + 1] = task end
	return false
end

-- Runs every alive task's final and forgets all tasks. Called before a reload or server stop throws the state away.
function I.stop_all_tasks()
	for task in pairs(alive) do
		co_task[task.co] = nil
		finish(task, false)
	end
	buckets = {}
end

-- Called by Java once per server tick.
function I.tick()
	I.flush_slots()
	now = now + 1
	local bucket = buckets[now]
	if not bucket then return end
	buckets[now] = nil
	for i = 1, #bucket do
		local task = bucket[i]
		if task.alive and task.wake == now then step(task) end
	end
end

-- ---------------------------------------------------------------------------------------------------------------
-- Public functions
-- ---------------------------------------------------------------------------------------------------------------

--[[doc
id: task
name: task
kind: type
group: Tasks
words: task handle alive running
syntax: t.alive   t.id
lead: A running function, from run{ }. t.alive is true until it ended; t.id is the id given to run{ } (or nil). Fields can't be changed. Stop it with stop{ task = t }.
status: unconfirmed
]]
local TASK_META = {
	__index = function(handle, key)
		local task = handles[handle]
		if key == "alive" then return task.alive end
		if key == "id" then return task.id end
		return nil
	end,
	__newindex = function(_, key) error("task fields can't be changed (t." .. tostring(key) .. ")", 2) end,
	__tostring = function() return "task" end, -- FORMAT
	__metatable = false,
}

-- "Same function" for ids means the same place in the code, so a function written inside an event handler (a new
-- function value on every event) still counts as the same.
local function place_of(f)
	local info = debug_getinfo(f, "S")
	return info.source .. ":" .. info.linedefined
end

--[[doc
name: run{ }
kind: doing
group: Tasks
words: task start coroutine async thread
syntax: run{ f, args = { ... }, final = function, id = value }
lead: Starts f as a task that may wait. Returns the task, or nil if a task of the same function with that id is running.
arg: 1 | function | The function to run.
arg: args? | table | A list: f gets args[1], args[2], ...; final gets the same values.
arg: final? | function | Runs when the task ends, also on stop, error, reload and server stop. Never waits.
arg: id? | any | One running task per function and id. Same function means the same place in the code, so a function written inside a handler counts as one.
returns: task · nil
note: A task (and every event handler) that runs longer than the config's script_time_limit (5 s) without waiting is stopped with an error at its next call into the game; its final still runs. A loop that never calls into the game (pure math) can't be stopped; after twice the limit the console names the event or task that is stuck. Keep long work in steps with wait( ).
status: unconfirmed
]]
function lib.run(a)
	if type(a) ~= "table" then error("run needs { }: run{ f, args = { ... } }", 2) end
	local f, args, final, id = a[1], a.args, a.final, a.id
	if type(f) ~= "function" then error("run: the first value must be a function, got " .. I.type_name(f), 2) end
	if args ~= nil and type(args) ~= "table" then error("run: args must be a table { ... }, got " .. I.type_name(args), 2) end
	if final ~= nil and type(final) ~= "function" then error("run: final must be a function, got " .. I.type_name(final), 2) end
	local slot
	if id ~= nil then
		local place = place_of(f)
		slot = ids[place]
		if not slot then slot = {}; ids[place] = slot end
		local running = slot[id]
		if running and running.alive then return nil end
	end
	local n = args and maxn(args) or 0
	local copy = { n = n }
	for i = 1, n do copy[i] = args[i] end
	local caller = debug_getinfo(2, "Sl")
	local origin = "run{} at " .. caller.short_src .. " line " .. caller.currentline -- FORMAT
	local handle = setmetatable({}, TASK_META)
	local task = { f = f, args = copy, origin = origin, final = final, id = id, id_slot = slot, alive = true }
	handles[handle] = task
	I.kind_of[handle] = "task"
	-- start like I.start_task, but the handle must exist before the task's first line runs
	alive[task] = true
	if slot then slot[id] = task end
	local co = pool[#pool]
	if co then pool[#pool] = nil else co = co_create(worker) end
	task.co = co
	co_task[co] = task
	step(task, task)
	return handle
end

-- Durations: a number is seconds, text is a number with one unit: t (tick, 50 ms), s, m, h, d. No combinations.
local UNIT_MS = { t = 50, s = 1000, m = 60000, h = 3600000, d = 86400000 }

local function duration_ms(value)
	if type(value) == "number" then return value >= 0 and value * 1000 or nil end
	if type(value) == "string" then
		local number, unit = value:match("^%s*(%d*%.?%d+)%s*([tsmhd]?)%s*$")
		if number then return tonumber(number) * UNIT_MS[unit ~= "" and unit or "s"] end
	end
	return nil
end
I.duration_ms = duration_ms

--[[doc
name: wait( )
kind: reading
group: Tasks
words: sleep delay pause later
syntax: wait( number | text )
lead: Pauses the task. A number is seconds; text is a number with one unit: t (tick), s, m, h, d.
arg: duration | number · text | 2, "3t", "2s", "1m", "1h", "1d".
returns: nothing
note: Only inside a task (an event handler, a command function or run{ }). Inside final it is ignored with a warning.
status: unconfirmed
]]
function lib.wait(duration)
	local co = co_running()
	if (in_final[co or I.MAIN] or 0) > 0 then
		I.warn("wait inside final is ignored (final always finishes at once)", 2)
		return
	end
	local task = co and co_task[co]
	if not task then error("wait only works inside a task (an event handler or run{})", 2) end
	local ms = duration_ms(duration)
	if not ms then
		error("wait: needs seconds or text like \"3t\", \"2s\", \"1m\", \"1h\", \"1d\", got " .. tostring(duration), 2)
	end
	if ms > DAY_MS and I.config.long_wait_warning then
		I.warn("waiting longer than a day: tasks end on reload and restart, store a time in script data instead "
			.. "(this warning can be turned off in config.lua)", 2)
	end
	co_yield(WAIT, max(1, ceil(ms / 50)))
end

--[[doc
name: stop{ }
kind: doing
group: Tasks
words: cancel end kill
syntax: stop{ task = task, force = boolean }
lead: Ends a task and runs its final. Returns false if the task had already ended.
arg: task | task | The task from run{ }.
arg: force? | boolean | true skips final.
returns: boolean
status: unconfirmed
]]
function lib.stop(a)
	if type(a) ~= "table" then error("stop needs { }: stop{ task = t }", 2) end
	local task = a.task ~= nil and handles[a.task]
	if not task then error("stop: task must be a task, got " .. I.type_name(a.task), 2) end
	if not task.alive then return false end
	local force = a.force == true
	local status = co_status(task.co)
	if status == "running" then -- a task stopping itself: unwind it from here
		task.force_stop = force
		error(STOP, 0)
	elseif status == "normal" then -- it is busy starting another task: it stops as soon as that hands back control
		task.pending_stop = force
	else -- waiting
		co_task[task.co] = nil
		finish(task, force)
	end
	return true
end
