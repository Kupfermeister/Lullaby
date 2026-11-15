-- Lullaby library, file 2 of 9: the type list (Kupfermeister 2026-11-15).
-- The agreed description of every type and every conversion. It is data: the library reads it (type_of, and later
-- argument checks, error texts, event fields, packet fields, command arguments, saving, docs), so it stays the one
-- place a type is described. Most types here are not built yet (see `later` and the build order in Kupfermeister's notes).
--
-- THE RULE: a type exists only if Minecraft (or the server) really has that structure where Lua meets the game
-- (a packet field, an event field, a server function). Nothing is invented. Plain fields are checked plain Lua
-- values (section RULES), not types.
--
-- THREE KINDS
--   plain   Lua's own values: nil, boolean, number, string, table, function, thread (coroutine).
--   value   data with a shape. ALWAYS YOUR OWN COPY. "A change goes where the value lives": changing a value that
--           sits inside an event, a packet or another value changes that; a value read from an object (for
--           example a player's held item, field name still to be agreed) is a copy, and the game changes only when
--           an action applies it.
--   object  a place or a being that exists once (player, world, block, ...): == and table keys work. Reading gives
--           the live state now. Objects never accept assignment, only actions change them. When the thing behind
--           it is gone (offline player, removed entity, unloaded chunk) its fields read nil and actions return
--           false, never an error. Reading never loads a chunk.
--   Memory hook: places and beings are objects, what is in them is a value.
--
-- HOW VALUES LOOK (field `impl` below)
--   small     a real Lua table with a shared metatable (methods, ==, tostring). Java sends plain numbers.
--   interned  vanilla keeps each one exactly once; Lua keeps one table per vanilla id, so == is free. Unchangeable,
--             swapped with :with{ }.
--   lazy      a small Lua table pointing at an unchangeable Java copy. Only the top level is lazy: a part (e.g.
--             it.components) loads WHOLE on its first touch, as real Lua tables (pairs, ipairs and # work as
--             normal). Lullaby's pairs loads the remaining parts first. On the way back to Java untouched parts
--             go as "unchanged" (free), touched parts as their tables, checked by the vanilla codec. Passing an
--             untouched value on (give{ item = e.item }) costs nothing.
--   special   own rules, written in the entry.
--   object    a slot number in Java (not a LuaJava object), cached weakly in Lua so each exists once.
--
-- ACCEPTED FORMS (field `accepts`): every value type takes the same three, in this order of preference:
--   (a) a value of that type
--   (b) a table in codec shape: the same shape as datapack JSON, so the wiki's datapack pages apply directly
--   (c) vanilla command text ("diamond_sword[enchantments={sharpness:5}]", "oak_stairs[facing=north]")
--   tostring(v) gives form (c), so printed text can be pasted back. Exception: component (a string is MiniMessage,
--   tostring gives MiniMessage). Strings inside form (b) tables written in Lua are MiniMessage at text positions
--   (TEXT_POSITIONS below); from_json(text, type) keeps vanilla's own meaning so datapack files work unchanged.
--   Everything is strict: "5" is not a number, 2.5 where a whole number is needed is an error, not 2.
--
-- SAVING (field `save`): NBT { type = <name>, data_version = <Minecraft's>, value = ... }. On load an older
--   data_version runs vanilla's DataFixer for `fix` (References.java). Objects save by identity and load back as
--   the same object. Types with save = false give an error on the line that tries.
--
-- Fields of an entry:
--   kind, impl     see above
--   vanilla        the Minecraft class (Mojang names) or "Bukkit ..." where Paper has it and vanilla does not
--   fields         what Lua sees (small values) or the top-level parts (lazy values)
--   codec, stream  vanilla Codec / StreamCodec constants used to convert (nil: none in vanilla, see notes)
--   text           vanilla text parser for form (c) (nil: no text form)
--   fix            DataFixer reference for saved data (nil: none)
--   arg            vanilla Brigadier argument for script commands (nil: none)
--   save           how it is saved
--   later          true: listed for completeness, built when its area is built
-- Function and method names in `methods` and in this header (type_of, from_json, :with) are proposals: each one
-- needs Kupfermeister's yes when it is built. Everything else here was agreed with Kupfermeister (2026-11-15). `later = true` types are built
-- when their area is built; the entries are descriptions; code that uses them is added step by step.
--
-- Like every library file this gets three tables from LuaRuntime.java when it is loaded: J (raw Java functions),
-- lib (what scripts see) and I (internal, shared by the library files). See core.lua.
local J, lib, I = ...

local T = {}
I.types = T

-- =================================================================================================================
-- RULES: plain fields (no own type). One checker each; results of id lookups are cached per Lua state.
-- =================================================================================================================

I.rules = {
	number   = { vanilla = "Byte, Short, Int, VarInt, Long, VarLong, Float, Double, Angle and the unsigned ones",
	             lua = "a normal Lua number (a double, the same type vanilla uses for positions): every int, float and "
	                 .. "double is exact, so precise coordinates never break because of Lullaby",
	             check = "whole and in range where the field needs it (2.5 or 3000000000 into an int field is an "
	                 .. "error, never cut or wrapped); NaN and infinity are errors where vanilla can't take them",
	             text = "number -> text (tostring, print, messages, JSON, text forms) uses the SHORTEST text that reads "
	                 .. "back as exactly the same number (Lua's own uses 14 digits and would lose precision)" },
	long64   = { vanilla = "Long / VarLong values above 2^53 (9007199254740992): only ids and bit patterns",
	             lua = "LuaJIT's built-in 64-bit whole number, ONLY when the value does not fit a normal number; "
	                 .. "otherwise a normal number. Works like a number: + - * / % ^, < > == also against normal "
	                 .. "numbers, the bit library on all 64 bits. Exact for all 64 bits.",
	             fields = "world seed (spawn info), sound seeds, structure block seed, keep_alive id, chat salts, "
	                 .. "ping request / pong response time",
	             lullaby = "tostring, print, messages, JSON and saving show plain digits (no \"LL\"); type_of says "
	                 .. "\"number\"; the wire and NBT carry all 64 bits exactly",
	             limits = "as table keys two equal 64-bit numbers are different keys (use tostring(seed)); fractions "
	                 .. "are dropped (seed + 0.5 == seed); math.floor and the like refuse them with a clear error "
	                 .. "(\"math.floor: 64-bit numbers (ids like seeds) can't be rounded\"), so math stays full speed",
	             note = "game time, milliseconds and timestamps never get near 2^53 (2^53 ticks = 14 million years). "
	                 .. "These values are plain values with no reach into memory; ffi stays locked (checked by a "
	                 .. "test in build step 1)" },
	boolean  = { vanilla = "Boolean" },
	string   = { vanilla = "String", check = "valid UTF-8, the field's max length" },
	id       = { vanilla = "Identifier, ResourceKey, registry references",
	             accepts = "\"stone\" or \"minecraft:stone\"", gives = "\"minecraft:stone\" (always full)",
	             check = "it exists in that field's registry (cached)", arg = "IdentifierArgument / ResourceArgument" },
	holder   = { vanilla = "\"ID or X\" (Holder<T>): sounds, chat types, dialogs, instruments, variants, ...",
	             accepts = "an id string, or a table with the full definition in codec shape",
	             gives = "the id if vanilla sent an id, otherwise the table (vanilla's own Holder distinction)" },
	tag_ref  = { vanilla = "TagKey / HolderSet", accepts = "\"#minecraft:logs\" or a list of ids",
	             arg = "ResourceOrTagArgument" },
	enum     = { vanilla = "Java enums (GameType, Direction, InteractionHand, EquipmentSlot, SoundSource, Pose, ...)",
	             lua = "the lowercase vanilla name (\"survival\", \"north\", \"main_hand\")",
	             arg = "StringRepresentableArgument (GameModeArgument, HeightmapTypeArgument, ...)" },
	uuid     = { vanilla = "UUID", lua = "string, lowercase with dashes", arg = "UuidArgument" },
	bytes    = { vanilla = "byte[]", lua = "a Lua string holding the raw bytes" },
	numbers  = { vanilla = "int[], VarInt[], long[] of plain numbers", lua = "list of numbers",
	             note = "long[] that packs bits (chunk heightmaps, debug samples) is NOT this rule: it stays inside "
	                 .. "its packet as bytes (8 per long)" },
	bitset   = { vanilla = "BitSet, FixedBitSet", lua = "list of true/false" },
	optional = { vanilla = "Optional<X>", lua = "the value, or nil" },
	either   = { vanilla = "Either<A, B>", lua = "whichever of the two it is (told apart by its kind)" },
	list     = { vanilla = "arrays, List<X>, Collection<X>", lua = "Lua list (1, 2, 3, ...)" },
	map      = { vanilla = "Map<K, V>", lua = "Lua table key = value" },
	duration = { lua = "number of seconds, or text with one unit: t (tick), s, m, h, d (\"3t\", \"2s\")",
	             gives = "milliseconds inside Lullaby; ticks where Minecraft wants ticks (rounded up, at least 1)",
	             arg = "TimeArgument (\"1d\", \"20s\", \"5000t\")" },
	color    = { vanilla = "int ARGB/RGB fields",
	             accepts = "a number, \"#rrggbb\" (alpha becomes ff) or \"#aarrggbb\", in EVERY color field",
	             gives = "always the full ARGB number (alpha ff where the field has none), so scripts never need "
	                 .. "to know the field's kind",
	             note = "into a field without transparency the alpha is dropped (vanilla can't store it)",
	             arg = "HexColorArgument" },
	time     = { vanilla = "Instant", lua = "number of milliseconds since 1970 (formatting: standard library, later)" },
}

-- =================================================================================================================
-- SMALL VALUES (Lua tables, copied). Methods are plain Lua: math never crosses to Java.
-- =================================================================================================================

T.block_pos = {
	kind = "value", impl = "small", vanilla = "net.minecraft.core.BlockPos",
	fields = { x = "whole number", y = "whole number", z = "whole number" },
	accepts = "block_pos, { x, y, z } or { x = , y = , z = } with whole numbers, text \"10 64 -5\"",
	codec = "BlockPos.CODEC (3 ints)", stream = "BlockPos.STREAM_CODEC", text = "BlockPosArgument",
	arg = "BlockPosArgument (~ ~1 ~)", save = "codec",
	methods = "+ - with block_pos, offset(dx, dy, dz), vec3() (the corner), chunk_pos(), section_pos() (only offset: "
		.. "no above/below/north/..., Kupfermeister 2026-11-15)",
}
T.vec3 = {
	kind = "value", impl = "small", vanilla = "net.minecraft.world.phys.Vec3",
	fields = { x = "number", y = "number", z = "number" },
	accepts = "vec3, { x, y, z } or { x = , y = , z = }, text \"0.5 64 0.5\"",
	codec = "Vec3.CODEC (3 doubles)", stream = "Vec3.STREAM_CODEC (LP_STREAM_CODEC for velocities)",
	text = "Vec3Argument", arg = "Vec3Argument", save = "codec",
	methods = "+ - * / (by a number), unary -, length, normalize, dot, cross, distance_to, lerp, x_rot y_rot z_rot "
		.. "(degrees), rotate(quaternion), block_pos() (rounds down)",
	note = "also used for vanilla's Vector3f fields (display entity scale and translation: rounded to float at the "
		.. "border, as vanilla does) and Rotations (armor stand angles): same handling, so one type (Kupfermeister 2026-11-15). "
		.. "block_pos, section_pos and chunk_pos stay separate: whole numbers with their own handling.",
}
T.chunk_pos = {
	kind = "value", impl = "small", vanilla = "net.minecraft.world.level.ChunkPos (a 16x16 column, full height)",
	fields = { x = "whole number", z = "whole number" },
	accepts = "chunk_pos, { x, z }, text \"10 -5\"", codec = "ChunkPos.CODEC (2 ints)",
	stream = "ChunkPos.STREAM_CODEC", arg = "ColumnPosArgument", save = "codec", methods = "offset(dx, dz)",
}
T.section_pos = {
	kind = "value", impl = "small", vanilla = "net.minecraft.core.SectionPos (a 16x16x16 cube)",
	fields = { x = "whole number", y = "whole number", z = "whole number" },
	accepts = "section_pos, { x, y, z }", stream = "SectionPos.STREAM_CODEC (one long)",
	save = "own: list of 3 ints", note = "vanilla has no Codec for it",
	methods = "offset(dx, dy, dz), chunk_pos(), contains(block_pos)",
}
T.global_pos = {
	kind = "value", impl = "small", vanilla = "net.minecraft.core.GlobalPos (death location, lodestone, spawn)",
	fields = { dimension = "id", pos = "block_pos" },
	accepts = "global_pos, { dimension = , pos = }, block (object)", codec = "GlobalPos.CODEC",
	stream = "GlobalPos.STREAM_CODEC", save = "codec",
}
T.yaw_pitch = {
	kind = "value", impl = "small", vanilla = "yRot/xRot floats (net.minecraft.world.phys.Vec2 in commands)",
	fields = { yaw = "number (degrees)", pitch = "number (degrees)" },
	accepts = "yaw_pitch, { yaw, pitch }, text \"~ 0\"", arg = "RotationArgument (AngleArgument for yaw only)",
	save = "own: list of 2 floats", note = "packets send it as angle bytes; Lullaby converts",
	methods = "direction() -> vec3; yaw_pitch.from_direction(vec3)",
}
T.quaternion = {
	kind = "value", impl = "small", vanilla = "org.joml.Quaternionf (display entity rotations)",
	fields = { x = "number", y = "number", z = "number", w = "number" },
	accepts = "quaternion, { x, y, z, w }, axis-angle table { angle = , axis = vec3 } (vanilla's second codec form)",
	codec = "ExtraCodecs.QUATERNIONF", stream = "ByteBufCodecs.QUATERNIONF", save = "codec",
	methods = "* (combine), rotate(vec3); quaternion.from_yaw_pitch(yp), quaternion.from_axis_angle(axis, degrees)",
}
T.aabb = {
	kind = "value", impl = "small", vanilla = "net.minecraft.world.phys.AABB (hitboxes, collisions; areas)",
	fields = { min = "vec3", max = "vec3" }, accepts = "aabb, { min = , max = }, two corners",
	save = "own: list of 6 doubles",
	note = "vanilla has neither Codec nor StreamCodec for it. Other area shapes (spheres, polygons, meshes from "
		.. "Blender, block masks) may come later: area functions must not assume every area is an aabb.",
	methods = "contains(vec3) (max side open), intersects(aabb), grow(n), move(vec3), center(), size()",
}
T.bounding_box = {
	kind = "value", impl = "small", vanilla = "net.minecraft.world.level.levelgen.structure.BoundingBox (structures)",
	fields = { min = "block_pos", max = "block_pos" }, accepts = "bounding_box, { min = , max = }",
	codec = "BoundingBox.CODEC (6 ints)", stream = "BoundingBox.STREAM_CODEC", save = "codec",
}
T.location = {
	kind = "value", impl = "small", vanilla = "Bukkit Location (every Paper event uses it; vanilla has no such type)",
	fields = { world = "world (object)", pos = "vec3", rot = "yaw_pitch" },
	accepts = "location, { world = , pos = , rot = } (rot optional: 0, 0, as in vanilla), entity (its location), block (its center)",
	save = "own: { world = id, pos, rot }", note = "built from world + vec3 + yaw_pitch; replaces today's location",
	methods = "block() (the block object at it), offset(vec3)",
}

-- =================================================================================================================
-- INTERNED VALUE
-- =================================================================================================================

T.block_state = {
	kind = "value", impl = "interned", vanilla = "net.minecraft.world.level.block.state.BlockState",
	fields = { type = "id of the block (\"minecraft:oak_stairs\")", ["<property>"] = "e.g. s.facing == \"north\"" },
	accepts = "block_state, text \"oak_stairs[facing=north]\" (missing properties take their default)",
	codec = "BlockState.CODEC", stream = "global id (Block.BLOCK_STATE_REGISTRY)",
	text = "BlockStateParser.parseForBlock", arg = "BlockStateArgument", fix = "BLOCK_STATE", save = "codec",
	methods = "with{ facing = \"south\" } (vanilla setValue, one call)",
	note = "Java sends the global id, Lua keeps one table per id. Replaces material for blocks.",
}

-- =================================================================================================================
-- LAZY VALUES (vanilla codecs through one DynamicOps adapter and the string.buffer wire)
-- =================================================================================================================

T.item_stack = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.item.ItemStack",
	fields = { id = "id of the item", count = "whole number", components = "components" },
	accepts = "item_stack, { id = , count = , components = }, text \"diamond_sword[damage=5]\"",
	codec = "ItemStack.CODEC (OPTIONAL_CODEC where empty is allowed)", stream = "ItemStack.OPTIONAL_STREAM_CODEC",
	text = "ItemParser.parse", arg = "ItemArgument", fix = "ITEM_STACK", save = "codec",
	note = "the empty stack (air, count 0) is nil. Replaces material for items. As vanilla as possible: no "
		.. "checks of Lullaby's own (Kupfermeister 2026-11-15); a new id keeps the patch (vanilla's transmuteCopy); tostring "
		.. "has no count, like vanilla's item text; == is vanilla's ItemStack.matches. Live item stacks are "
		.. "rejected: vanilla swaps stack objects, so a live one would silently stop tracking.",
}
T.components = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.core.component.DataComponentPatch",
	fields = { ["<component id>"] = "its value in codec shape (\"custom_name\" and \"minecraft:custom_name\" both work)" },
	accepts = "components, table of component id = value",
	codec = "DataComponentPatch.CODEC", stream = "DataComponentPatch.STREAM_CODEC",
	text = "inside ItemParser's [ ]", fix = "DATA_COMPONENTS", save = "codec",
	note = "EXACTLY vanilla's patch, nothing added (Kupfermeister 2026-11-15): only the changes against the item type's "
		.. "defaults, keys as vanilla writes them (\"minecraft:custom_name\"); short keys are looked up too. A "
		.. "removed default is vanilla's \"!minecraft:food\" = {}; setting an entry to nil takes it out of the patch "
		.. "(back to the default). Reading defaults would be its own function (later). Loads whole on first touch.",
}
T.component = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.network.chat.Component (text)",
	accepts = "component, MiniMessage string, JSON text (from_json), table in codec shape",
	codec = "ComponentSerialization.CODEC", stream = "ComponentSerialization.STREAM_CODEC (TRUSTED_ where vanilla uses it)",
	arg = "ComponentArgument", fix = "TEXT_COMPONENT", save = "codec",
	note = "tostring gives MiniMessage. Messages go out as System Chat Message packets.",
}
T.nbt = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.nbt.Tag / CompoundTag",
	accepts = "nbt, Lua table, SNBT text \"{Health:20}\"",
	codec = "CompoundTag.CODEC (passthrough) / NbtOps", stream = "ByteBufCodecs.TAG / COMPOUND_TAG",
	text = "TagParser.parseCompoundFully", arg = "CompoundTagArgument (NbtTagArgument for any tag)", save = "as is",
	note = "Lua has one number kind, NBT has six. Writing back: existing keys keep their kind (a byte stays "
		.. "a byte); new keys become int if whole, else double. An exact-kind helper (nbt.byte(5)) comes only "
		.. "when a script needs it (Kupfermeister's yes then).",
}
T.particle = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.core.particles.ParticleOptions",
	fields = { type = "id of the particle", ["<option>"] = "its data (dust: color, scale; block: block_state; ...)" },
	accepts = "particle, \"flame\", { type = \"dust\", color = , scale = }, text \"dust{color:[1,0,0],scale:1}\"",
	codec = "ParticleOptions.CODEC", stream = "ParticleOptions.STREAM_CODEC", text = "ParticleArgument.readParticle",
	arg = "ParticleArgument", fix = "PARTICLE", save = "codec",
}
T.effect = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.effect.MobEffectInstance",
	fields = { id = "id of the effect", amplifier = "whole number", duration = "duration", ambient = "boolean",
		show_particles = "boolean", show_icon = "boolean" },
	accepts = "effect, table in codec shape", codec = "MobEffectInstance.CODEC",
	stream = "MobEffectInstance.STREAM_CODEC", save = "codec",
}
T.attribute_modifier = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.entity.ai.attributes.AttributeModifier",
	fields = { id = "id", amount = "number", operation = "enum" },
	accepts = "attribute_modifier, table in codec shape", codec = "AttributeModifier.CODEC",
	stream = "AttributeModifier.STREAM_CODEC", save = "codec",
}
T.profile = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.item.component.ResolvableProfile",
	fields = { name = "string or nil", uuid = "uuid or nil", properties = "list (textures: base64 value)",
		skin = "skin override (body, cape, elytra, model) or nil" },
	accepts = "profile, player name, table in codec shape (textures alone is enough for a head)",
	codec = "ResolvableProfile.CODEC", stream = "ResolvableProfile.STREAM_CODEC", save = "codec",
	note = "the tab list's full GameProfile (needs a uuid) is a field inside its packet, not this type",
}
T.dialog = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.server.dialog.Dialog", later = true,
	accepts = "dialog, id (registered dialog), table in codec shape",
	codec = "Dialog.DIRECT_CODEC (Dialog.CODEC for id-or-inline)", stream = "Dialog.STREAM_CODEC",
	arg = "ResourceOrIdArgument.DialogArgument", save = "codec",
}
T.chat_type = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.network.chat.ChatType.Bound", later = true,
	fields = { type = "holder (chat type)", name = "component", target = "component or nil" },
	stream = "ChatType.Bound.STREAM_CODEC", save = false,
	note = "no Codec in vanilla: the packet generator writes its conversion from the StreamCodec. This is the "
		.. "BOUND chat type (a chat type plus who sent it), not the chat_type registry entry, which is an id",
}
T.number_format = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.network.chat.numbers.NumberFormat (sidebar, tab list)",
	accepts = "number_format, { type = \"blank\" | \"styled\" | \"fixed\", ... }", later = true,
	codec = "NumberFormatTypes.CODEC", stream = "NumberFormatTypes.STREAM_CODEC", save = "codec",
}
T.merchant_offer = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.item.trading.MerchantOffer", later = true,
	accepts = "merchant_offer, table in codec shape", codec = "MerchantOffer.CODEC",
	stream = "MerchantOffer.STREAM_CODEC", fix = "VILLAGER_TRADE", save = "codec",
}
T.entity_data = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.network.syncher.SynchedEntityData.DataValue list",
	fields = { ["<data id>"] = "its value. The data id is SynchedEntityData's number for one entity field (e.g. "
		.. "the custom name); its serializer decides the value's type, using the types of this file" },
	save = false, later = true,
	note = "no Codec: each EntityDataSerializer has its own StreamCodec; the generator writes the conversion",
}
T.advancement = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.advancements.Advancement", later = true,
	codec = "Advancement.CODEC", stream = "Advancement.STREAM_CODEC", save = "codec",
}
T.recipe_display = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.item.crafting.display.RecipeDisplay", later = true,
	codec = "RecipeDisplay.CODEC", stream = "RecipeDisplay.STREAM_CODEC", save = "codec",
}
T.slot_display = {
	kind = "value", impl = "lazy", vanilla = "net.minecraft.world.item.crafting.display.SlotDisplay", later = true,
	codec = "SlotDisplay.CODEC", stream = "SlotDisplay.STREAM_CODEC", save = "codec",
}

-- Item components whose value holds text: here a plain string means MiniMessage (see ACCEPTED FORMS in the header). Generated later from
-- DataComponents.java; a build check fails if a new component uses the text codec and is not listed.
I.TEXT_POSITIONS = {
	custom_name = "the value", item_name = "the value", lore = "each line",
	written_book_content = "pages[].raw and pages[].filtered (title and author are plain strings)",
	attribute_modifiers = "modifiers[].display.value (when display type is override)",
	sign_text_front = "messages[] and filtered_messages[] (4 lines each)",
	sign_text_back = "messages[] and filtered_messages[] (4 lines each)",
	instrument = "description (inline definition only)", provides_trim_material = "description (inline only)",
	trim = "material.description, pattern.description (inline only)",
	jukebox_playable = "song.description (inline song definition only)",
	-- container, bundle_contents, charged_projectiles hold item stacks: their components follow these same rules.
	-- custom_data, entity_data, block_entity_data, bucket_entity_data are raw NBT: strings there stay strings.
}

-- =================================================================================================================
-- SPECIAL VALUES
-- =================================================================================================================

T.packet = {
	kind = "value", impl = "special", vanilla = "net.minecraft.network.protocol.Packet (one description per packet)",
	fields = { ["<field>"] = "Mojang's field names in snake_case, typed with this file's types and rules" },
	accepts = "packet, { type = \"<packet id>\", <fields> }",
	save = false,
	note = "names: vanilla packet ids plus direction and phase. Read field by field (a chunk packet costs nothing "
		.. "until its data is read). In on{} changes apply and it may be cancelled; after{} only reads. Over after "
		.. "the first wait. Hooks: incoming before vanilla's main-thread handling (PacketProcessor); outgoing "
		.. "before Connection.send hands the packet to the network thread (a bytecode hook; packets sent from "
		.. "other threads are held on the network thread instead). Field lists and Java glue are generated from Minecraft's code at build time; packets "
		.. "the generator cannot build stay read-only until hand glue exists. Structures used by only one packet "
		.. "(player info entries, boss bar operations, map decorations, chunk and light data) live in its "
		.. "description, not as types.",
}
T.damage = {
	kind = "value", impl = "special (works like a small value: a plain Lua table, but holds objects)",
	vanilla = "net.minecraft.world.damagesource.DamageSource",
	fields = { type = "id (damage type)", direct = "entity or nil", causing = "entity or nil", pos = "vec3 or nil" },
	accepts = "damage, damage type id, { type = , direct = , causing = , pos = }", save = false,
	note = "vanilla has no codec for it: a small table of an id and objects, built in Lua",
}

-- =================================================================================================================
-- OBJECTS (exist once; slot numbers in Java; weak cache in Lua)
-- =================================================================================================================

T.server = {
	kind = "object", vanilla = "net.minecraft.server.MinecraftServer", identity = "one constant `server`",
	save = false, note = "server.online_players replaces online_players(), which is then REMOVED (no old names "
		.. "kept). Fields later: worlds, tick rate, motd, whitelist, ...",
}
T.player = {
	kind = "object", vanilla = "net.minecraft.server.level.ServerPlayer", identity = "uuid (online or offline)",
	accepts = "player, name or uuid text", arg = "EntityArgument (players only; GameProfileArgument for offline)",
	save = "uuid",
	note = "ONE object online and offline: p.online tells which. Each action says if it works offline; offline "
		.. "reads/changes of inventory, location or data read or write the player file: a worker thread does the disk work on its own copy, the script waits (like wait) and gets the result back on the main thread.",
}
T.entity = {
	kind = "object", vanilla = "net.minecraft.world.entity.Entity (all kinds; a player is an entity too)",
	identity = "uuid; packets use the network id, Lullaby converts", arg = "EntityArgument (@e, @p, names)",
	save = "uuid", note = "removed: fields read nil, actions return false",
}
T.world = {
	kind = "object", vanilla = "net.minecraft.server.level.ServerLevel", identity = "dimension id",
	accepts = "world, id text (\"overworld\")", arg = "DimensionArgument", save = "id",
}
T.chunk = {
	kind = "object", vanilla = "net.minecraft.world.level.chunk.LevelChunk", identity = "world + chunk_pos",
	save = "world id + chunk_pos", note = "never loaded by a read",
}
T.section = {
	kind = "object", vanilla = "net.minecraft.world.level.chunk.LevelChunkSection (16x16x16 of a chunk)",
	identity = "world + section_pos", save = "world id + section_pos",
	note = "the unit for big edits: worker threads get a COPY, never the live section",
}
T.block = {
	kind = "object", vanilla = "a PLACE: world + block_pos (a placed block has no identity of its own)",
	identity = "world + block_pos", accepts = "block, location (the block it is in)", save = "world id + block_pos",
	fields = { state = "block_state", block_entity = "block_entity or nil", location = "location" },
	note = "data saved on a block stays on the place until a script removes it",
}
T.block_entity = {
	kind = "object", vanilla = "net.minecraft.world.level.block.entity.BlockEntity (chest, sign, furnace, ...)",
	identity = "the block entity itself: replaced when its block changes", save = "world id + block_pos",
	note = "its own saved data dies with the block (vanilla). Reached as b.block_entity",
}
T.inventory = {
	kind = "object", vanilla = "net.minecraft.world.Container (a chest's slots, a player's inventory)",
	identity = "its holder", arg = "SlotArgument / SlotsArgument for slots", save = false,
}
T.menu = {
	kind = "object", vanilla = "net.minecraft.world.inventory.AbstractContainerMenu (an open window, real or fake)",
	identity = "the open window", save = false, later = true,
}
T.world_border = { kind = "object", vanilla = "net.minecraft.world.level.border.WorldBorder", identity = "its world", save = false, later = true }
T.bossbar      = { kind = "object", vanilla = "net.minecraft.server.level.ServerBossEvent", identity = "its uuid", save = false, later = true }
T.team         = { kind = "object", vanilla = "net.minecraft.world.scores.PlayerTeam", identity = "name", arg = "TeamArgument", save = "name", later = true }
T.objective    = { kind = "object", vanilla = "net.minecraft.world.scores.Objective", identity = "name", arg = "ObjectiveArgument", save = "name", later = true }
T.map          = { kind = "object", vanilla = "net.minecraft.world.level.saveddata.maps.MapItemSavedData", identity = "map id", save = "map id", later = true }
T.task  = { kind = "object", vanilla = "Lullaby's own (run{})", identity = "the running function", save = false }
T.event = { kind = "object", vanilla = "Lullaby's own view of a Paper event", identity = "the event while it runs",
            save = false, note = "over after the first wait; fields mapped with EVENT_JAVA_TYPES" }

-- =================================================================================================================
-- COMMAND ARGUMENTS: command{} arg types are this file's types. Kept from today: word, text (rest of the line),
-- integer, number, bool. More vanilla arguments, used when their area is built:
--   EntityAnchorArgument (eyes/feet), SwizzleArgument (axes), Vec2Argument, ScoreHolderArgument,
--   ScoreboardSlotArgument, OperationArgument, ObjectiveCriteriaArgument, NbtPathArgument, BlockPredicateArgument,
--   ItemPredicateArgument, StyleArgument, TeamColorArgument, SlotSourceArgument, SwingAnimationArgument,
--   FunctionArgument, RangeArgument (int/float ranges "1..5"), MessageArgument (signed chat).
-- =================================================================================================================

-- =================================================================================================================
-- EVENT FIELDS: Java types from Paper's event getters/setters -> types of this file. Collected 2026-11-15 from
-- paper-api 26.3 build 249 (all getters/setters of every event class). Every conversion COPIES (Bukkit's Vector,
-- Location and ItemStack are changeable Java objects; Lullaby never hands them out).
-- A getter whose Java type is not listed gives an error when the event is first used (never a silent nil); the
-- type is then added here. Sub-interfaces (LivingEntity, Villager, Creeper, ...) map like their parent.
-- =================================================================================================================

I.EVENT_JAVA_TYPES = {
	-- plain (rules)
	["boolean"] = "boolean", ["byte"] = "number", ["int"] = "number", ["long"] = "number", ["float"] = "number",
	["double"] = "number", ["java.lang.String"] = "string", ["java.util.UUID"] = "uuid",
	["java.net.InetAddress"] = "string (\"1.2.3.4\")", ["java.net.SocketAddress"] = "string (\"1.2.3.4:25565\")",
	["int[]"] = "list of numbers", ["java.lang.String[]"] = "list of strings",
	-- objects
	["org.bukkit.entity.Player"] = "player", ["org.bukkit.OfflinePlayer"] = "player",
	["org.bukkit.entity.Entity"] = "entity (also every entity sub-interface: LivingEntity, HumanEntity, Item, "
		.. "Projectile, ArmorStand, Villager, ...)",
	["org.bukkit.block.Block"] = "block", ["org.bukkit.World"] = "world", ["org.bukkit.Chunk"] = "chunk",
	["org.bukkit.WorldBorder"] = "world_border",
	["org.bukkit.inventory.Inventory"] = "inventory (also its sub-interfaces: CraftingInventory, AnvilInventory, ...)",
	["org.bukkit.inventory.InventoryHolder"] = "the holder's object (player, entity or block_entity)",
	["org.bukkit.inventory.InventoryView"] = "menu (also AnvilView, MerchantView, EnchantmentView)",
	["org.bukkit.block.TileState"] = "block_entity (Sign, Lectern, Beacon, CreatureSpawner, TrialSpawner, ...: "
		.. "Bukkit gives a snapshot; Lullaby gives the live block_entity)",
	["org.bukkit.Raid"] = "raid (object, later: not in the list yet)",
	["org.bukkit.boss.DragonBattle"] = "dragon_fight (object, later: not in the list yet)",
	["org.bukkit.map.MapView"] = "map",
	["org.bukkit.command.CommandSender"] = "player, entity or server (whoever sent it)",
	["io.papermc.paper.command.brigadier.CommandSourceStack"] = "player, entity or server (whoever sent it)",
	-- values
	["org.bukkit.inventory.ItemStack"] = "item_stack", ["org.bukkit.Location"] = "location",
	["org.bukkit.util.Vector"] = "vec3", ["org.bukkit.util.BoundingBox"] = "aabb",
	["io.papermc.paper.math.BlockPosition"] = "block_pos",
	["org.bukkit.block.data.BlockData"] = "block_state",
	["org.bukkit.block.BlockState"] = "{ block = block, state = block_state } (Bukkit's snapshot of a block that "
		.. "is about to change: the place plus the state it will get)",
	["net.kyori.adventure.text.Component"] = "component", ["org.bukkit.potion.PotionEffect"] = "effect",
	["org.bukkit.damage.DamageSource"] = "damage", ["com.destroystokyo.paper.profile.PlayerProfile"] = "profile",
	["org.bukkit.inventory.MerchantRecipe"] = "merchant_offer",
	["net.kyori.adventure.nbt.api.BinaryTagHolder"] = "nbt",
	["io.papermc.paper.dialog.DialogResponseView"] = "nbt (the dialog's answers)",
	["net.kyori.adventure.sound.Sound"] = "holder (sound) plus source, volume, pitch, seed: one table",
	["org.bukkit.inventory.meta.BookMeta"] = "components (the book's written_book_content)",
	-- ids (registry things)
	["org.bukkit.Material"] = "id", ["org.bukkit.entity.EntityType"] = "id",
	["org.bukkit.potion.PotionEffectType"] = "id", ["org.bukkit.enchantments.Enchantment"] = "id",
	["org.bukkit.Sound"] = "id", ["org.bukkit.GameEvent"] = "id", ["org.bukkit.block.banner.PatternType"] = "id",
	["org.bukkit.generator.structure.Structure"] = "id", ["org.bukkit.loot.LootTable"] = "id",
	["org.bukkit.NamespacedKey"] = "id", ["net.kyori.adventure.key.Key"] = "id", ["org.bukkit.GameRule"] = "id",
	["org.bukkit.inventory.MenuType"] = "id", ["org.bukkit.entity.Villager$Profession"] = "id",
	["org.bukkit.Instrument"] = "id", ["org.bukkit.Statistic"] = "id", ["org.bukkit.advancement.Advancement"] = "id",
	["org.bukkit.inventory.Recipe"] = "id (the recipe's id; also CookingRecipe, CraftingRecipe, ...)",
	-- enums (rule): lowercase vanilla names
	["org.bukkit.inventory.EquipmentSlot"] = "enum", ["org.bukkit.block.BlockFace"] = "enum",
	["org.bukkit.block.sign.Side"] = "enum", ["org.bukkit.PortalType"] = "enum", ["org.bukkit.DyeColor"] = "enum",
	["org.bukkit.Difficulty"] = "enum", ["org.bukkit.GameMode"] = "enum", ["org.bukkit.SoundCategory"] = "enum",
	["org.bukkit.entity.Pose"] = "enum", ["org.bukkit.inventory.MainHand"] = "enum", ["org.bukkit.TreeType"] = "enum",
	["org.bukkit.ExplosionResult"] = "enum", ["org.bukkit.entity.EnderDragon$Phase"] = "enum",
	["org.bukkit.entity.Spellcaster$Spell"] = "enum", ["org.bukkit.entity.FishHook$HookState"] = "enum",
	["org.bukkit.block.data.type.Vault$State"] = "enum", ["org.bukkit.Note"] = "number (note 0..24)",
	["org.bukkit.Input"] = "table of booleans (forward, backward, left, right, jump, sneak, sprint)",
	-- collections: List/Set/Collection<X> -> list of X; Map<K, V> -> map of K = V (enum and id keys as strings)
	-- NOT mapped (left out on purpose): connection objects (login/configuration phase, later with packets),
	-- Plugin and RegisteredServiceProvider (links to other plugins, later), CachedServerIcon, ServerLinks,
	-- LootContext, BlockTransformer/EntityTransformer maps, IntUnaryOperator, Throwable,
	-- PersistentDataContainerView (saved data, later), EnchantmentOffer[] (enchanting table, later).
}
