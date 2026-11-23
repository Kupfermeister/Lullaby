-- Lullaby's built-in script, copied here on the first start. Everything in it is off (comments).
-- Remove the "--" at the start of lines to try them.
-- Every .lua file in this folder (and its folders) loads; names starting with "--" are on standby (not loaded).

-- Greet players when they join:
-- on{ "PlayerJoinEvent", function(event)
--   message{ player = event.player, text = "Welcome, " .. event.player.name .. "!" }
-- end }

-- A countdown as its own task (the player is passed in args):
-- local function countdown(player)
--   for i = 3, 1, -1 do
--     message{ player = player, text = tostring(i) }
--     wait(1)
--   end
-- end
-- on{ "PlayerJoinEvent", function(event) run{ countdown, args = { event.player } } end }

-- A command: /pay <target> <amount>
-- command{ "pay", function(context)
--   message{ player = context.args.target, text = "You got " .. context.args.amount .. " coins" }
-- end, args = { { name = "target", type = "player" }, { name = "amount", type = "integer", min = 1 } } }
