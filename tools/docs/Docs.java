import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Lullaby's docs generator. Not part of the plugin: a single-file Java program, run with
 * `java tools/docs/Docs.java <lib dir> <out dir>` (Gradle task `docs`, and the GitHub job on every push).
 *
 * Bricks, each replaceable on its own; they only meet in docs.json (the ONE fixed contract, see FORMAT below):
 *   reader   - reads the `--[[doc ... ]]` blocks in lib/*.lua and finds the connections ("uses" / "used by")
 *   builders - docs.json -> index.html (site.html with the data put in), lullaby.md, lullaby.d.lua (VS Code)
 * First it checks that every file named in ARCHITECTURE.md and README.md exists (see checkPaths), and after reading
 * that every `function lib.NAME(` has a doc block (see checkDocumented).
 *
 * A doc block sits right above `function lib.NAME(` and has one `key: value` per line:
 *   name, kind (doing | reading | event | type | lua), group, syntax, lead, note, returns, example (code; indented
 *   lines below continue it), status (confirmed | unconfirmed), words (extra search words), arg: name | types | text
 *   (repeatable), expert: key | value (repeatable). Topic pages without a function (tools/docs/lua.txt) give id:.
 * "confirmed" means a human reviewed the text. Everything is published; unconfirmed texts carry a warning.
 *
 * Connections are found, not written: inside a function's body, a call of another documented library function
 * (`lib.name(` or `lib.name{`) or of a raw Java function (`J.name(`) counts as "uses". Raw Java functions get their own
 * grayed pages (kind "java"), made from the calls alone, with no handwritten text.
 *
 * FORMAT of docs.json (version 1). New fields may be added at any time; builders ignore fields they don't know.
 *   { "version": 1, "minecraft": "...", "entries": [ { "id", "name", "kind", "group", "syntax", "lead", "note",
 *     "returns", "example", "status", "words", "args": [[name, types, text]], "expert": [[key, value]], "src": "file:line",
 *     "uses": [id], "usedBy": [id] } ] }
 */
public final class Docs {

    // A block ends at a line that is just "]]". Right after it may come `function lib.NAME(` (the block's id); a block
    // without that names itself with `id:` (topic pages, e.g. the Lua pages in tools/docs/lua.txt).
    private static final Pattern BLOCK = Pattern.compile("--\\[\\[doc\\n(.*?)\\n]]\\n(?:function lib\\.(\\w+)\\()?",
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
        Path here = Path.of("tools/docs"); // run from the project folder
        checkPaths("ARCHITECTURE.md", "README.md");

        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, String> bodies = new HashMap<>();
        List<Path> files;
        try (Stream<Path> s = Files.list(lib)) { files = s.filter(p -> p.toString().endsWith(".lua")).sorted().toList(); }
        read(here.resolve("lua.txt"), "tools/docs/lua.txt", entries, bodies); // first: the Lua group leads the sidebar
        for (Path file : files) read(file, "src/main/resources/lullaby/lib/" + file.getFileName(), entries, bodies);
        checkDocumented(files, entries);

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

        // Everything is shown; texts no human has reviewed yet (status unconfirmed) carry a warning on the page.
        List<Entry> shown = new ArrayList<>(entries.values());

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
        System.out.println("docs: " + shown.size() + " entries -> " + out);
    }

    // ---- path check ------------------------------------------------------------------------------------------

    // Links to files ("[x](src/...)", not http or #) and paths in backticks that start at a project folder.
    // Paths with * or < are patterns ("lib/*.lua", "lullaby-<version>.jar"), not files.
    private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
    private static final Pattern CODE_PATH = Pattern.compile("`((?:src|tools|gradle|\\.github)/[^`\\s*<]*)`");

    /**
     * The architecture page and the README name files; every named file must exist, so a moved or deleted file
     * can't leave the pages wrong quietly. Stops the docs build (exit code 1) with a list of what's missing.
     */
    private static void checkPaths(String... pages) throws IOException {
        Set<String> missing = new LinkedHashSet<>(); // a path named twice is listed once
        for (String page : pages) {
            String text = Files.readString(Path.of(page), StandardCharsets.UTF_8);
            List<String> paths = new ArrayList<>();
            Matcher link = LINK.matcher(text);
            while (link.find()) {
                String target = link.group(1);
                if (target.contains("://") || target.startsWith("#") || target.startsWith("mailto:")) continue;
                paths.add(target.replaceAll("#.*$", "").replaceAll(":\\d+$", ""));
            }
            Matcher code = CODE_PATH.matcher(text);
            while (code.find()) paths.add(code.group(1));
            for (String path : paths) if (!Files.exists(Path.of(path))) missing.add(page + ": " + path);
        }
        if (missing.isEmpty()) return;
        // FORMAT: docs build, files named on the architecture page or README that don't exist
        System.err.println("docs: these files are named but don't exist (update the page in the same change):");
        for (String m : missing) System.err.println("  " + m);
        System.exit(1);
    }

    // A library function scripts see: `function lib.NAME(` at the start of a line. Lua's own functions handed through
    // (`lib.pairs = ...` in core.lua) are not matched; the Lua pages in lua.txt cover them.
    private static final Pattern LIB_FUNCTION = Pattern.compile("(?m)^function lib\\.(\\w+)\\(");

    /**
     * Every library function scripts can use needs a doc block right above it, so a new one can't reach scripts
     * undocumented. Stops the docs build (exit code 1) with each missing function's file and line.
     */
    private static void checkDocumented(List<Path> files, Map<String, Entry> entries) throws IOException {
        List<String> missing = new ArrayList<>();
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
            Matcher m = LIB_FUNCTION.matcher(text);
            while (m.find()) {
                if (entries.containsKey(m.group(1))) continue;
                int line = 1 + (int) text.substring(0, m.start()).chars().filter(c -> c == '\n').count();
                missing.add("src/main/resources/lullaby/lib/" + file.getFileName() + ":" + line + "  lib." + m.group(1));
            }
        }
        if (missing.isEmpty()) return;
        // FORMAT: docs build, library functions without a doc block
        System.err.println("docs: these library functions have no --[[doc block right above them (add one in the same change):");
        for (String m : missing) System.err.println("  " + m);
        System.exit(1);
    }

    // ---- reader ----------------------------------------------------------------------------------------------

    private static void read(Path file, String src, Map<String, Entry> entries, Map<String, String> bodies)
            throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            Map<String, String> f = new LinkedHashMap<>();
            List<String[]> args = new ArrayList<>(), expert = new ArrayList<>();
            // `key: value` per line; an indented line (tab or 2 spaces) or an empty line continues the key before it,
            // keeping the line break (for `example:` code).
            String last = null;
            for (String line : m.group(1).split("\n", -1)) {
                if (last != null && (line.isBlank() || line.startsWith("\t") || line.startsWith("  "))) {
                    String more = line.startsWith("\t") ? line.substring(1) : line.startsWith("  ") ? line.substring(2) : "";
                    f.merge(last, more, (a, b) -> a.isEmpty() ? b : a + "\n" + b);
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String key = line.substring(0, colon).trim(), value = line.substring(colon + 1).trim();
                last = null;
                if (key.equals("arg")) args.add(split(value, 3));
                else if (key.equals("expert")) expert.add(split(value, 2));
                else { f.merge(key, value, (a, b) -> a + " " + b); last = key; }
            }
            for (var kv : f.entrySet()) kv.setValue(kv.getValue().stripTrailing());
            String id = m.group(2) != null ? m.group(2) : f.get("id");
            if (id == null) throw new IllegalStateException(src + ": a doc block without id: and without function lib.NAME");
            f.put("id", id);
            f.putIfAbsent("name", id);
            f.putIfAbsent("kind", "doing");
            f.putIfAbsent("group", "Other");
            f.putIfAbsent("status", "unconfirmed");
            int at = m.group(2) != null ? m.start(2) : m.start();
            int line = 1 + (int) text.substring(0, at).chars().filter(c -> c == '\n').count();
            f.put("src", src + ":" + line);
            entries.put(id, new Entry(f, args, expert, new LinkedHashSet<>(), new LinkedHashSet<>()));
            if (m.group(2) == null) continue; // a topic page: no code, no connections
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
        Set<String> tables = new HashSet<>(); // "java" of java.class: declared once, before its first function
        for (Entry e : list) {
            String kind = e.f().get("kind");
            if (kind.equals("java") || kind.equals("type") || kind.equals("lua")) continue;
            b.append('\n');
            int dot = e.id().indexOf('.');
            if (dot > 0 && tables.add(e.id().substring(0, dot))) b.append(e.id(), 0, dot).append(" = {}\n");
            if (e.f().containsKey("lead")) b.append("--- ").append(e.f().get("lead")).append('\n');
            boolean named = e.f().getOrDefault("syntax", "").contains(e.id() + "{");
            if (named && !e.args().isEmpty()) {
                b.append("---@class lullaby.").append(e.id()).append("_args\n");
                for (String[] a : e.args())
                    for (String n : a[0].replace("?", "").split("/"))
                        b.append("---@field ").append(n.trim().matches("\\d+") ? "[" + n.trim() + "]" : n.trim()).append("? ").append(luaType(a[1])).append(' ').append(a[2]).append('\n');
                b.append("---@param a lullaby.").append(e.id()).append("_args\nfunction ").append(e.id()).append("(a) end\n");
            } else {
                for (String[] a : e.args()) b.append("---@param ").append(a[0].replace("?", "").split("/")[0].trim()).append(' ').append(luaType(a[1])).append('\n');
                StringJoiner params = new StringJoiner(", ");
                for (String[] a : e.args()) params.add(a[0].replace("?", "").split("/")[0].trim());
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
