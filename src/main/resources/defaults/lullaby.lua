-- Lullaby's built-in script, copied here on the first start. Everything in it is off (comments), so Lullaby
-- changes nothing in the game until you write scripts. Remove the "--" at the start of lines to try them.
-- Every .lua file in this folder (and its folders) loads; names starting with "--" are on standby (not loaded).

-- Greet players when they join:
-- on{ "PlayerJoinEvent", function(e)
--   message{ player = e.player, text = "<green>Welcome, " .. e.player.name .. "!" }
-- end }

-- A countdown as its own task (the player is passed in args):
-- local function countdown(p)
--   for i = 3, 1, -1 do
--     message{ player = p, text = "<yellow>" .. i }
--     wait(1)
--   end
-- end
-- on{ "PlayerJoinEvent", function(e) run{ countdown, args = { e.player } } end }
