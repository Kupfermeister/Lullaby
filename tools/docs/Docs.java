import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lullaby's docs generator (Kupfermeister 2026-11-15). Not part of the plugin: a single-file Java program, run with
 * `java tools/docs/Docs.java <lib dir> <out dir> [drafts]` (Gradle task `docs`, and the GitHub job on every push).
 *
 * Bricks, each replaceable on its own; they only meet in docs.json (the ONE fixed contract, see FORMAT below):
 *   reader   - reads the `--[[doc ... ]]` blocks in lib/*.lua and finds the connections ("uses" / "used by")
 *   builders - docs.json -> index.html (site.html with the data put in), lullaby.md, lullaby.d.lua (VS Code)
 *
 * A doc block sits right above `function lib.NAME(` and has one `key: value` per line:
 *   name, kind (doing | reading | event | type), group, syntax, lead, note, returns, status (confirmed | unconfirmed),
 *   words (extra search words), arg: name | types | text (repeatable), expert: key | value (repeatable).
 * Every handwritten text needs Kupfermeister's yes: entries stay "unconfirmed" and are left out of the public site until then
 * (the `drafts` argument shows them, for review).
 *
 * Connections are found, not written: inside a function's body, a call of another documented library function
 * (`lib.name(` or `lib.name{`) or of a raw Java function (`J.name(`) counts as "uses". Raw Java functions get their own
 * grayed pages (kind "java"), made from the calls alone, with no handwritten text.
 *
 * FORMAT of docs.json (version 1). New fields may be added at any time; builders ignore fields they don't know.
 *   { "version": 1, "minecraft": "...", "entries": [ { "id", "name", "kind", "group", "syntax", "lead", "note",
 *     "returns", "status", "words", "args": [[name, types, text]], "expert": [[key, value]], "src": "file:line",
 *     "uses": [id], "usedBy": [id] } ] }
 */
public final class Docs {

    private static final Pattern BLOCK = Pattern.compile("--\\[\\[doc\\r?\\n(.*?)]]\\s*\\r?\\nfunction lib\\.(\\w+)\\(",
            Pattern.DOTALL);
    // Library files run with the real _G as globals, so a plain `tostring(` is Lua's own: only `lib.name(` is lib's.
    private static final Pattern CALL = Pattern.compile("\\blib\\.(\\w+)\\s*[({]");
    private static final Pattern JCALL = Pattern.compile("\\bJ\\.(\\w+)\\s*\\(");

    record Entry(Map<String, String> f, List<String[]> args, List<String[]> expert, Set<String> uses,
                 Set<String> usedBy) {
        String id() { return f.get("id"); }
    }

    public static void main(String[] argv) throws IOException {
        Path lib = Path.of(argv[0]), out = Path.of(argv[1]);
        boolean drafts = argv.length > 2 && argv[2].equals("drafts");
        Path here = Path.of("tools/docs"); // run from the project folder

        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, String> bodies = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(lib)) { files = s.filter(p -> p.toString().endsWith(".lua")).sorted().toList(); }
        for (Path file : files) read(file, entries, bodies);

        // Connections: documented library functions and raw Java functions called inside each body.
        for (Entry e : List.copyOf(entries.values())) {
            String body = bodies.get(e.id());
            if (body == null) continue;
            Matcher m = CALL.matcher(body);
            while (m.find()) if (!m.group(1).equals(e.id()) && entries.containsKey(m.group(1))) e.uses().add(m.group(1));
            Matcher j = JCALL.matcher(body);
            while (j.find()) {
                String id = "J." + j.group(1);
                entries.computeIfAbsent(id, k -> javaEntry(k));
                e.uses().add(id);
            }
        }
        for (Entry e : entries.values()) for (String u : e.uses()) entries.get(u).usedBy().add(e.id());

        // Public site: no unconfirmed entries (and no links to them); raw Java pages only if something shown uses them.
        List<Entry> shown = new ArrayList<>();
        for (Entry e : entries.values()) {
            if (!drafts && "unconfirmed".equals(e.f().get("status"))) continue;
            shown.add(e);
        }
        Set<String> ids = new HashSet<>();
        for (Entry e : shown) ids.add(e.id());
        shown.removeIf(e -> e.f().get("kind").equals("java") && e.usedBy().stream().noneMatch(ids::contains));
        ids.clear();
        for (Entry e : shown) ids.add(e.id());
        for (Entry e : shown) { e.uses().retainAll(ids); e.usedBy().retainAll(ids); }

        String minecraft = minecraftVersion();
        String json = json(shown, minecraft);
        Files.createDirectories(out);
        Files.writeString(out.resolve("docs.json"), json);
        String site = Files.readString(here.resolve("site.html")).replace("/*DOCS*/null", json);
        Files.writeString(out.resolve("index.html"), site);
        Path fonts = out.resolve("fonts");
        Files.createDirectories(fonts);
        try (Stream<Path> s = Files.list(here.resolve("fonts"))) {
            for (Path font : s.toList()) Files.copy(font, fonts.resolve(font.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.writeString(out.resolve("lullaby.md"), markdown(shown, minecraft));
        Files.writeString(out.resolve("lullaby.d.lua"), luals(shown));
        System.out.println("docs: " + shown.size() + " entries -> " + out + (drafts ? " (with drafts)" : ""));
    }

    // ---- reader ----------------------------------------------------------------------------------------------

    private static void read(Path file, Map<String, Entry> entries, Map<String, String> bodies) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            String id = m.group(2);
            Map<String, String> f = new LinkedHashMap<>();
            List<String[]> args = new ArrayList<>(), expert = new ArrayList<>();
            f.put("id", id);
            for (String line : m.group(1).split("\n")) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                if (key.equals("arg")) args.add(split(value, 3));
                else if (key.equals("expert")) expert.add(split(value, 2));
                else f.merge(key, value, (a, b) -> a + " " + b);
            }
            f.putIfAbsent("name", id);
            f.putIfAbsent("kind", "doing");
            f.putIfAbsent("group", "Other");
            f.putIfAbsent("status", "unconfirmed");
            int line = 1 + (int) text.substring(0, m.start(2)).chars().filter(c -> c == '\n').count();
            f.put("src", "src/main/resources/lullaby/lib/" + file.getFileName() + ":" + line);
            entries.put(id, new Entry(f, args, expert, new LinkedHashSet<>(), new LinkedHashSet<>()));
            // Strings and comments are no calls.
            bodies.put(id, body(text, m.end()).replaceAll("--[^\\n]*", "")
                    .replaceAll("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'", "\"\""));
        }
    }

    /** The function body: from the header to the first `end` at the start of a line (library style: tabs inside). */
    private static String body(String text, int from) {
        int nl = text.indexOf('\n', from);
        String rest = text.substring(from, nl < 0 ? text.length() : nl);
        if (rest.contains(" end")) return rest; // one-line function
        Matcher end = Pattern.compile("(?m)^end\\b").matcher(text);
        return end.find(from) ? text.substring(from, end.start()) : text.substring(from);
    }

    private static String[] split(String value, int n) {
        String[] parts = value.split("\\|", n);
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = i < parts.length ? parts[i].trim() : "";
        return out;
    }

    private static Entry javaEntry(String id) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("id", id);
        f.put("name", id);
        f.put("kind", "java");
        f.put("group", "Java");
        f.put("syntax", id + "( … )");
        f.put("status", "confirmed"); // generated, no handwritten text
        f.put("src", "src/main/java/io/github/kupfermeister/lullaby/lua/JavaFunctions.java");
        return new Entry(f, new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    private static String minecraftVersion() throws IOException {
        for (String line : Files.readAllLines(Path.of("gradle.properties")))
            if (line.startsWith("minecraftVersion")) return line.substring(line.indexOf('=') + 1).trim();
        return "";
    }

    // ---- builders --------------------------------------------------------------------------------------------

    private static String json(List<Entry> list, String minecraft) {
        StringBuilder b = new StringBuilder("{\"version\":1,\"minecraft\":").append(q(minecraft)).append(",\"entries\":[");
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (i > 0) b.append(',');
            b.append("\n{");
            boolean first = true;
            for (var kv : e.f().entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(q(kv.getKey())).append(':').append(q(kv.getValue()));
            }
            b.append(",\"args\":").append(rows(e.args())).append(",\"expert\":").append(rows(e.expert()));
            b.append(",\"uses\":").append(strings(e.uses())).append(",\"usedBy\":").append(strings(e.usedBy())).append('}');
        }
        return b.append("\n]}").toString();
    }

    private static String rows(List<String[]> rows) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (String[] r : rows) j.add(strings(Arrays.asList(r)));
        return j.toString();
    }

    private static String strings(Collection<String> s) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (String x : s) j.add(q(x));
        return j.toString();
    }

    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '<' -> b.append("\\u003c"); // the JSON goes inside a <script>: no "</script>" can end it early
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String markdown(List<Entry> list, String minecraft) {
        StringBuilder b = new StringBuilder("# Lullaby library (Minecraft " + minecraft + ")\n\nGenerated by tools/docs/Docs.java; do not edit.\n");
        for (Entry e : list) {
            var f = e.f();
            b.append("\n## ").append(f.get("name")).append("\n\n`").append(f.getOrDefault("syntax", f.get("name"))).append("`\n");
            if (f.containsKey("lead")) b.append('\n').append(f.get("lead")).append('\n');
            if (f.containsKey("note")) b.append("\n> ⚠ ").append(f.get("note")).append('\n');
            if (!e.args().isEmpty()) {
                b.append("\n| argument | type | |\n|---|---|---|\n");
                for (String[] a : e.args()) b.append("| `").append(a[0]).append("` | ").append(a[1]).append(" | ").append(a[2]).append(" |\n");
            }
            if (f.containsKey("returns")) b.append("\nReturns: ").append(f.get("returns")).append('\n');
            if (!e.uses().isEmpty()) b.append("\nUses: ").append(String.join(", ", e.uses())).append('\n');
            if (!e.usedBy().isEmpty()) b.append("\nUsed by: ").append(String.join(", ", e.usedBy())).append('\n');
            b.append("\nSource: ").append(f.get("src")).append('\n');
        }
        return b.toString();
    }

    /** Definitions for the Lua language server (VS Code autocomplete). Types: "list of X" -> X[], text -> string. */
    private static String luals(List<Entry> list) {
        StringBuilder b = new StringBuilder("---@meta\n-- Lullaby library definitions for the Lua language server. Generated, do not edit.\n");
        for (Entry e : list) {
            String kind = e.f().get("kind");
            if (kind.equals("java") || kind.equals("type")) continue;
            b.append('\n');
            if (e.f().containsKey("lead")) b.append("--- ").append(e.f().get("lead")).append('\n');
            boolean named = e.f().getOrDefault("syntax", "").contains(e.id() + "{");
            if (named && !e.args().isEmpty()) {
                b.append("---@class lullaby.").append(e.id()).append("_args\n");
                for (String[] a : e.args())
                    for (String n : a[0].split("/"))
                        b.append("---@field ").append(n.trim()).append("? ").append(luaType(a[1])).append(' ').append(a[2]).append('\n');
                b.append("---@param a lullaby.").append(e.id()).append("_args\nfunction ").append(e.id()).append("(a) end\n");
            } else {
                for (String[] a : e.args()) b.append("---@param ").append(a[0].split("/")[0].trim()).append(' ').append(luaType(a[1])).append('\n');
                StringJoiner params = new StringJoiner(", ");
                for (String[] a : e.args()) params.add(a[0].split("/")[0].trim());
                b.append("function ").append(e.id()).append('(').append(params).append(") end\n");
            }
        }
        return b.toString();
    }

    private static String luaType(String types) {
        StringJoiner j = new StringJoiner("|");
        for (String t : types.split("·")) {
            t = t.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("list of ")) t = luaType(t.substring(8)) + "[]";
            else if (t.equals("text")) t = "string";
            j.add(t);
        }
        return j.length() == 0 ? "any" : j.toString();
    }
}
