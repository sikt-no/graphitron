package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Builds a per-directive parity report for a graphitron RC: which directives
 * the legacy {@code directives.graphqls} declares, which the rewrite's
 * {@code directives.graphqls} declares, where the argument shapes diverge,
 * and which directives have at least one execution-tier fixture exercising
 * them.
 *
 * <p>The execution-tier signal is "directive name appears as {@code @name}
 * somewhere in any {@code .graphqls} file under the given fixture
 * directories." This relies on the rewrite's "no codegen ⇒ classifier
 * rejects" rule: if a fixture schema mentions a directive and the
 * surrounding execution-tier test passes, the directive must validate and
 * generate. A directive that the rewrite recognises in its directive
 * declarations but that no fixture exercises is a parity gap (or a not-yet-
 * supported feature).
 *
 * <p>The SDL directive parser is a small purpose-built scanner. It is not a
 * full GraphQL parser; it only recognises directive declarations of the
 * shape used in our two {@code directives.graphqls} files (block-string
 * descriptions, line comments, optional argument lists with optional
 * defaults and inline {@code @deprecated}, and {@code on LOC | LOC}
 * locations). Bringing in a full GraphQL library for the roadmap-tool
 * would couple the build order; the surface here is small enough to read.
 */
final class DirectiveSupportReport {

    private DirectiveSupportReport() {}

    record Directive(String name, List<Arg> args, List<String> locations) {}

    record Arg(String name, String type, String defaultValue) {}

    /**
     * Per-directive support summary, keyed by directive name.
     */
    record Row(
        String name,
        boolean inLegacy,
        boolean inRewrite,
        boolean inFixtures,
        List<String> argDiff
    ) {}

    /**
     * CLI entry. Three positional args (legacy directives file, rewrite directives file,
     * colon-separated fixture directories) followed by optional flags:
     *
     * <ul>
     *   <li>{@code --mode=migration} — emit an AsciiDoc fragment suitable for
     *       {@code include::} from the migration guide, instead of the internal
     *       plain-text report.</li>
     *   <li>{@code --output=<path>} — write to file instead of stdout. Required when
     *       {@code --mode=migration} is set in CI; the docs build's verify-mode reads
     *       the regenerated content from disk.</li>
     * </ul>
     *
     * <p>The internal report (default) stays consumer-stable: the new flag is purely
     * additive, and the existing positional shape continues to work.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() < 3) {
            System.err.println(
                "usage: directive-support <legacy-directives.graphqls>"
                    + " <rewrite-directives.graphqls>"
                    + " <fixture-dir>[:<fixture-dir>...]"
                    + " [--mode=migration] [--output=<path>]");
            return 64;
        }
        Path legacyFile = Path.of(args.get(0)).toAbsolutePath().normalize();
        Path rewriteFile = Path.of(args.get(1)).toAbsolutePath().normalize();
        List<Path> fixtureDirs = new ArrayList<>();
        for (String s : args.get(2).split(":")) {
            fixtureDirs.add(Path.of(s).toAbsolutePath().normalize());
        }
        boolean migration = false;
        Path outputFile = null;
        for (int i = 3; i < args.size(); i++) {
            String a = args.get(i);
            if ("--mode=migration".equals(a)) migration = true;
            else if (a.startsWith("--output=")) outputFile = Path.of(a.substring("--output=".length()));
            else {
                System.err.println("directive-support: unknown arg: " + a);
                return 64;
            }
        }

        if (!Files.isRegularFile(legacyFile)) {
            System.err.println("not a file: " + legacyFile);
            return 64;
        }
        if (!Files.isRegularFile(rewriteFile)) {
            System.err.println("not a file: " + rewriteFile);
            return 64;
        }

        List<Directive> legacy = parseDirectives(Files.readString(legacyFile));
        List<Directive> rewrite = parseDirectives(Files.readString(rewriteFile));
        var fixtureUses = collectFixtureDirectives(fixtureDirs);

        String rendered = migration
            ? renderMigration(legacy, rewrite, fixtureUses)
            : render(legacy, rewrite, fixtureUses);
        if (outputFile != null) {
            Files.createDirectories(outputFile.getParent());
            Files.writeString(outputFile, rendered);
            System.out.println("wrote " + outputFile);
        } else {
            System.out.print(rendered);
        }
        return 0;
    }

    /**
     * Strips block strings ({@code """..."""}) and line comments ({@code # ...})
     * from a GraphQL SDL source so the directive scanner does not pick up
     * false positives inside descriptions or comments.
     *
     * <p>Block-string content is replaced with a single space (preserving
     * structure-significant boundaries); regular {@code "..."} string
     * literals are also collapsed to a space because they only appear inside
     * default values and inline directive arguments where their content is
     * not relevant for shape comparison.
     */
    static String stripCommentsAndStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            if (i + 2 < n && s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                // block string """..."""; skip until matching closing """
                i += 3;
                while (i < n) {
                    // \""" is the escape for embedding """ inside a block string
                    if (s.charAt(i) == '\\' && i + 3 < n
                        && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"' && s.charAt(i + 3) == '"') {
                        i += 4;
                        continue;
                    }
                    if (i + 2 < n && s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                        i += 3;
                        break;
                    }
                    i++;
                }
                out.append(' ');
                continue;
            }
            if (s.charAt(i) == '#') {
                while (i < n && s.charAt(i) != '\n') i++;
                continue;
            }
            if (s.charAt(i) == '"') {
                int j = i + 1;
                while (j < n) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j += 2;
                        continue;
                    }
                    if (s.charAt(j) == '"') {
                        j++;
                        break;
                    }
                    j++;
                }
                out.append(' ');
                i = j;
                continue;
            }
            out.append(s.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * Parses every {@code directive @name(...) on LOC | LOC} declaration in
     * the source. Argument descriptions and inline {@code @deprecated}
     * applications are stripped before per-arg shape extraction, so a
     * deprecation does not show up as an argument shape divergence.
     */
    static List<Directive> parseDirectives(String source) {
        String stripped = stripCommentsAndStrings(source);
        List<Directive> out = new ArrayList<>();
        Pattern decl = Pattern.compile("\\bdirective\\s+@([A-Za-z_][A-Za-z0-9_]*)");
        Matcher m = decl.matcher(stripped);
        int searchFrom = 0;
        while (m.find(searchFrom)) {
            String name = m.group(1);
            int p = m.end();
            p = skipWs(stripped, p);
            List<Arg> args = List.of();
            if (p < stripped.length() && stripped.charAt(p) == '(') {
                int close = matchParen(stripped, p);
                if (close < 0) {
                    searchFrom = m.end();
                    continue;
                }
                args = parseArgs(stripped.substring(p + 1, close));
                p = close + 1;
            }
            p = skipWs(stripped, p);
            if (stripped.startsWith("repeatable", p)) {
                p += "repeatable".length();
                p = skipWs(stripped, p);
            }
            if (!stripped.startsWith("on", p)
                || (p + 2 < stripped.length() && Character.isJavaIdentifierPart(stripped.charAt(p + 2)))) {
                searchFrom = m.end();
                continue;
            }
            p += 2;
            List<String> locations = new ArrayList<>();
            while (true) {
                p = skipWs(stripped, p);
                int s = p;
                while (p < stripped.length()
                    && (Character.isLetterOrDigit(stripped.charAt(p)) || stripped.charAt(p) == '_')) {
                    p++;
                }
                if (p == s) break;
                String tok = stripped.substring(s, p);
                if (!tok.equals(tok.toUpperCase()) || !Character.isLetter(tok.charAt(0))) {
                    p = s;
                    break;
                }
                locations.add(tok);
                p = skipWs(stripped, p);
                if (p < stripped.length() && stripped.charAt(p) == '|') {
                    p++;
                } else {
                    break;
                }
            }
            out.add(new Directive(name, args, locations));
            searchFrom = p;
        }
        return out;
    }

    private static int skipWs(String s, int p) {
        while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++;
        return p;
    }

    private static int matchParen(String s, int open) {
        int depth = 1;
        int i = open + 1;
        while (i < s.length() && depth > 0) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            if (depth == 0) return i;
            i++;
        }
        return -1;
    }

    /**
     * Strips {@code @name} and {@code @name(...)} sequences from an argument
     * body so inline {@code @deprecated(reason: "...")} on an argument does
     * not interfere with the per-arg walker.
     */
    static String stripInlineDirectives(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        int n = body.length();
        while (i < n) {
            if (body.charAt(i) == '@') {
                i++;
                while (i < n && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_')) i++;
                int j = i;
                while (j < n && Character.isWhitespace(body.charAt(j))) j++;
                if (j < n && body.charAt(j) == '(') {
                    int close = matchParen(body, j);
                    if (close < 0) {
                        return out.toString();
                    }
                    i = close + 1;
                }
                out.append(' ');
                continue;
            }
            out.append(body.charAt(i));
            i++;
        }
        return out.toString();
    }

    /**
     * Walks a directive's argument list, returning {@code (name, type,
     * defaultValue)} per argument. Arguments may be separated by commas or
     * by whitespace (both are legal SDL); inline directives have already
     * been stripped.
     */
    static List<Arg> parseArgs(String rawBody) {
        String body = stripInlineDirectives(rawBody);
        List<Arg> args = new ArrayList<>();
        int i = 0;
        int n = body.length();
        while (i < n) {
            while (i < n && (Character.isWhitespace(body.charAt(i)) || body.charAt(i) == ',')) i++;
            if (i >= n) break;

            int s = i;
            while (i < n && (Character.isLetterOrDigit(body.charAt(i)) || body.charAt(i) == '_')) i++;
            if (i == s) {
                i++;
                continue;
            }
            String argName = body.substring(s, i);

            i = skipWs(body, i);
            if (i >= n || body.charAt(i) != ':') {
                continue;
            }
            i++;
            i = skipWs(body, i);

            int ts = i;
            while (i < n) {
                char c = body.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '_' || c == '[' || c == ']' || c == '!') {
                    i++;
                } else {
                    break;
                }
            }
            String type = body.substring(ts, i);

            i = skipWs(body, i);
            String defaultValue = null;
            if (i < n && body.charAt(i) == '=') {
                i++;
                i = skipWs(body, i);
                int ds = i;
                int depth = 0;
                while (i < n) {
                    char c = body.charAt(i);
                    if (c == '[' || c == '(' || c == '{') {
                        depth++;
                        i++;
                        continue;
                    }
                    if (c == ']' || c == ')' || c == '}') {
                        depth--;
                        i++;
                        continue;
                    }
                    if (depth == 0) {
                        if (c == ',') break;
                        if (Character.isWhitespace(c) && peeksNextArgBoundary(body, i)) break;
                    }
                    i++;
                }
                defaultValue = body.substring(ds, i).trim();
                if (defaultValue.isEmpty()) defaultValue = null;
            }

            args.add(new Arg(argName, type, defaultValue));
        }
        return args;
    }

    private static boolean peeksNextArgBoundary(String body, int from) {
        int j = from;
        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
        if (j >= body.length()) return false;
        char first = body.charAt(j);
        if (!Character.isLetter(first) && first != '_') return false;
        int k = j;
        while (k < body.length() && (Character.isLetterOrDigit(body.charAt(k)) || body.charAt(k) == '_')) k++;
        while (k < body.length() && Character.isWhitespace(body.charAt(k))) k++;
        return k < body.length() && body.charAt(k) == ':';
    }

    /**
     * Walks each fixture directory recursively and returns the set of
     * directive names referenced in any {@code .graphqls} file under it.
     * Comments and string literals are stripped first so a {@code @ref}
     * mention inside a description text does not register as a use.
     */
    static java.util.Set<String> collectFixtureDirectives(List<Path> dirs) throws IOException {
        var found = new TreeSet<String>();
        Pattern use = Pattern.compile("@([A-Za-z_][A-Za-z0-9_]*)");
        for (Path dir : dirs) {
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : (Iterable<Path>) walk::iterator) {
                    if (!Files.isRegularFile(p)) continue;
                    if (!p.getFileName().toString().endsWith(".graphqls")) continue;
                    String text = stripCommentsAndStrings(Files.readString(p));
                    Matcher m = use.matcher(text);
                    while (m.find()) found.add(m.group(1));
                }
            }
        }
        return found;
    }

    /**
     * Builds the report given parsed legacy/rewrite directive lists and the
     * set of directive names observed in fixture schemas. Returns the
     * formatted multi-line text.
     */
    static String render(
        List<Directive> legacy,
        List<Directive> rewrite,
        java.util.Set<String> fixtureUses
    ) {
        Map<String, Directive> legacyByName = new LinkedHashMap<>();
        for (Directive d : legacy) legacyByName.put(d.name(), d);
        Map<String, Directive> rewriteByName = new LinkedHashMap<>();
        for (Directive d : rewrite) rewriteByName.put(d.name(), d);

        var allNames = new TreeSet<String>();
        allNames.addAll(legacyByName.keySet());
        allNames.addAll(rewriteByName.keySet());

        List<Row> rows = new ArrayList<>();
        for (String name : allNames) {
            Directive l = legacyByName.get(name);
            Directive r = rewriteByName.get(name);
            boolean inFix = fixtureUses.contains(name);
            List<String> diff = (l != null && r != null) ? argShapeDiff(l, r) : List.of();
            rows.add(new Row(name, l != null, r != null, inFix, diff));
        }

        StringBuilder out = new StringBuilder();
        out.append("Directive parity report\n");
        out.append("=======================\n\n");

        out.append("Per-directive support tier:\n");
        out.append("  L = declared in legacy directives.graphqls\n");
        out.append("  R = declared in rewrite directives.graphqls\n");
        out.append("  X = referenced in at least one execution-tier fixture schema\n");
        out.append("  ! = argument-shape divergence between legacy and rewrite\n\n");

        int colName = Math.max(20, allNames.stream().mapToInt(String::length).max().orElse(20) + 4);
        for (Row row : rows) {
            String flags = ""
                + (row.inLegacy() ? "L" : "-")
                + (row.inRewrite() ? "R" : "-")
                + (row.inFixtures() ? "X" : "-")
                + (!row.argDiff().isEmpty() ? "!" : "-");
            out.append("  ")
                .append(pad("@" + row.name(), colName))
                .append("  [").append(flags).append("]\n");
        }

        out.append("\nLegacy-only directives:\n");
        var legacyOnly = rows.stream().filter(r -> r.inLegacy() && !r.inRewrite()).toList();
        if (legacyOnly.isEmpty()) out.append("  (none)\n");
        else for (Row r : legacyOnly) out.append("  @").append(r.name()).append('\n');

        out.append("\nRewrite-only directives:\n");
        var rewriteOnly = rows.stream().filter(r -> !r.inLegacy() && r.inRewrite()).toList();
        if (rewriteOnly.isEmpty()) out.append("  (none)\n");
        else for (Row r : rewriteOnly) out.append("  @").append(r.name()).append('\n');

        out.append("\nRewrite-declared directives without execution-tier coverage:\n");
        var noFix = rows.stream().filter(r -> r.inRewrite() && !r.inFixtures()).toList();
        if (noFix.isEmpty()) out.append("  (none)\n");
        else for (Row r : noFix) out.append("  @").append(r.name()).append('\n');

        out.append("\nArgument-shape divergences (rewrite vs legacy):\n");
        var diverged = rows.stream().filter(r -> !r.argDiff().isEmpty()).toList();
        if (diverged.isEmpty()) {
            out.append("  (none)\n");
        } else {
            for (Row r : diverged) {
                out.append("  @").append(r.name()).append('\n');
                for (String line : r.argDiff()) {
                    out.append("    ").append(line).append('\n');
                }
            }
        }

        out.append('\n');
        out.append("Counts:\n");
        out.append("  total directive names              : ").append(allNames.size()).append('\n');
        out.append("  declared in both legacy and rewrite: ").append(rows.stream().filter(r -> r.inLegacy() && r.inRewrite()).count()).append('\n');
        out.append("  legacy-only                        : ").append(legacyOnly.size()).append('\n');
        out.append("  rewrite-only                       : ").append(rewriteOnly.size()).append('\n');
        out.append("  rewrite without fixture coverage   : ").append(noFix.size()).append('\n');
        out.append("  argument-shape divergences         : ").append(diverged.size()).append('\n');
        return out.toString();
    }

    /**
     * Migration-guide fragment: consumer-facing AsciiDoc listing the directives the rewrite
     * supports, the legacy-only directives a migrating consumer must drop or replace, and the
     * argument-shape changes that affect existing schema files. Internal counts and
     * fixture-coverage detail are elided; the migration audience does not need them.
     */
    static String renderMigration(
        List<Directive> legacy,
        List<Directive> rewrite,
        java.util.Set<String> fixtureUses
    ) {
        Map<String, Directive> legacyByName = new LinkedHashMap<>();
        for (Directive d : legacy) legacyByName.put(d.name(), d);
        Map<String, Directive> rewriteByName = new LinkedHashMap<>();
        for (Directive d : rewrite) rewriteByName.put(d.name(), d);
        var allNames = new TreeSet<String>();
        allNames.addAll(legacyByName.keySet());
        allNames.addAll(rewriteByName.keySet());

        StringBuilder out = new StringBuilder();
        out.append("// Generated by `graphitron-roadmap-tool directive-support --mode=migration`.\n");
        out.append("// Regenerate via the verify-mode CI guard. Never edit by hand.\n\n");

        out.append("=== Supported directives\n\n");
        out.append("The rewrite generator declares the following directives. Each is documented in ")
           .append("the architecture chapter and exercised by at least one execution-tier or ")
           .append("pipeline-tier test fixture:\n\n");
        var supported = allNames.stream()
            .filter(n -> rewriteByName.containsKey(n))
            .sorted()
            .toList();
        for (String name : supported) {
            out.append("* `@").append(name).append("`\n");
        }

        out.append("\n=== Legacy-only directives\n\n");
        var legacyOnly = allNames.stream()
            .filter(n -> legacyByName.containsKey(n) && !rewriteByName.containsKey(n))
            .sorted()
            .toList();
        if (legacyOnly.isEmpty()) {
            out.append("None. Every directive declared in legacy graphitron is also declared by the rewrite.\n");
        } else {
            out.append("These directives existed in legacy graphitron but are removed in the rewrite. ")
               .append("Drop them from your schema (or replace per the notes below) before migrating:\n\n");
            for (String name : legacyOnly) {
                out.append("* `@").append(name).append("`\n");
            }
        }

        out.append("\n=== Argument-shape changes\n\n");
        var changes = new ArrayList<Row>();
        for (String name : allNames) {
            Directive l = legacyByName.get(name);
            Directive r = rewriteByName.get(name);
            if (l == null || r == null) continue;
            List<String> diff = argShapeDiff(l, r);
            if (!diff.isEmpty()) changes.add(new Row(name, true, true, fixtureUses.contains(name), diff));
        }
        if (changes.isEmpty()) {
            out.append("No directive shared with legacy has changed argument shape; existing usages of supported directives migrate unchanged.\n");
        } else {
            out.append("The following directives have argument-shape changes between legacy and rewrite. Consumers must update existing usages:\n\n");
            for (Row row : changes) {
                out.append("==== `@").append(row.name()).append("`\n\n");
                for (String line : row.argDiff()) {
                    out.append("* ").append(line).append('\n');
                }
                out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Compares two directive declarations and produces human-readable diff
     * lines for each argument that exists on one side and not the other or
     * whose declared type / default differs.
     */
    static List<String> argShapeDiff(Directive legacy, Directive rewrite) {
        Map<String, Arg> legacyArgs = new LinkedHashMap<>();
        for (Arg a : legacy.args()) legacyArgs.put(a.name(), a);
        Map<String, Arg> rewriteArgs = new LinkedHashMap<>();
        for (Arg a : rewrite.args()) rewriteArgs.put(a.name(), a);

        var allArgs = new TreeMap<String, Boolean>();
        for (String n : legacyArgs.keySet()) allArgs.put(n, true);
        for (String n : rewriteArgs.keySet()) allArgs.put(n, true);

        List<String> diff = new ArrayList<>();
        for (String n : allArgs.keySet()) {
            Arg l = legacyArgs.get(n);
            Arg r = rewriteArgs.get(n);
            if (l == null) {
                diff.add("+ " + n + ": " + r.type() + (r.defaultValue() != null ? " = " + r.defaultValue() : "") + "  (rewrite only)");
            } else if (r == null) {
                diff.add("- " + n + ": " + l.type() + (l.defaultValue() != null ? " = " + l.defaultValue() : "") + "  (legacy only)");
            } else if (!Objects.equals(l.type(), r.type()) || !Objects.equals(l.defaultValue(), r.defaultValue())) {
                diff.add("~ " + n + ": legacy=" + describe(l) + " rewrite=" + describe(r));
            }
        }
        return diff;
    }

    private static String describe(Arg a) {
        return a.type() + (a.defaultValue() != null ? " = " + a.defaultValue() : "");
    }

    private static String pad(String s, int w) {
        if (s.length() >= w) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < w) sb.append(' ');
        return sb.toString();
    }
}
