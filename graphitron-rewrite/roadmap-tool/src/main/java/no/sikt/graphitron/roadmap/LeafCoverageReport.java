package no.sikt.graphitron.roadmap;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-processor that joins the per-module classifier-trace JSONL files (emitted by
 * {@code -Pleaf-coverage}) with the sealed leaf inventory parsed from the model sources
 * and the roadmap-mention index, then renders one row per leaf.
 *
 * <p>Two outputs:
 *
 * <ul>
 *   <li><b>Internal report</b> ({@code roadmap/inference-axis-coverage.adoc}). One row
 *       per leaf with hierarchy, leaf class, intent (one-line javadoc), trace count,
 *       distinct fixtures observed, highest tier observed (over the four-arm
 *       {@code unit < pipeline < compilation < execution} ordering), a separate
 *       {@code cross-cutting} flag for leaves exercised only by cross-cutting tests,
 *       the test classes that exercised the leaf, and roadmap mentions of the leaf
 *       class name.</li>
 *   <li><b>{@code --mode=migration} fragment</b>. A consumer-facing AsciiDoc snippet
 *       suitable for {@code include::} from the migration guide. Internal columns
 *       (trace count, fixtures, tier) elided; the row reads "supported" or "not yet
 *       supported".</li>
 * </ul>
 *
 * <p>Backed by embedded DuckDB. The connection is in-memory and ephemeral: open, register
 * the JSONL traces as a view via {@code read_json_auto} over the
 * {@code <root>/**\/target/leaf-coverage.jsonl} glob, stage the small parsed
 * {@code leaves} and {@code mentions} tables, run the aggregation queries, render, close.
 * No persisted {@code .duckdb} file. An auditor who wants ad-hoc pivots can point the
 * {@code duckdb} CLI at the JSONL traces directly.
 *
 * <p>Verify-mode short-circuits with a clear "no trace files found" diagnostic when the
 * glob matches nothing, so a contributor running roadmap-tool without
 * {@code -Pleaf-coverage} sees a useful message instead of a confusing drift report.
 */
final class LeafCoverageReport {

    /** Hierarchies whose sealed leaves the report enumerates. */
    private static final List<String> HIERARCHIES =
        List.of("GraphitronField", "RootField", "ChildField", "InputField", "GraphitronType");

    /** Tier ordering for the "highest tier observed" aggregation. */
    private static final List<String> TIER_ORDER =
        List.of("unit", "pipeline", "compilation", "execution");

    private LeafCoverageReport() {}

    /**
     * Entry point invoked by {@link Main}. Args:
     *
     * <ul>
     *   <li>{@code <root-dir>} — graphitron-rewrite root, used to resolve the trace glob,
     *       the model source files, and the roadmap directory.</li>
     *   <li>{@code [--verify]} — fails with non-zero exit when the on-disk report drifts
     *       from the regenerated content. Short-circuits with a "no trace files found"
     *       diagnostic when the glob matches nothing.</li>
     *   <li>{@code [--mode=migration]} — emit the migration-guide fragment instead of the
     *       internal report. Output path defaults to
     *       {@code <root-dir>/docs/manual/_generated/supported-schema-shapes.adoc};
     *       override with {@code --output=<path>}.</li>
     * </ul>
     */
    static int run(List<String> args) throws IOException {
        if (args.isEmpty()) {
            System.err.println("usage: leaf-coverage <root-dir> [--verify] [--mode=migration] [--output=<path>]");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        boolean verify = false;
        boolean migration = false;
        Path outputOverride = null;
        for (int i = 1; i < args.size(); i++) {
            String a = args.get(i);
            if ("--verify".equals(a)) verify = true;
            else if ("--mode=migration".equals(a)) migration = true;
            else if (a.startsWith("--output=")) outputOverride = Path.of(a.substring("--output=".length()));
            else {
                System.err.println("leaf-coverage: unknown arg: " + a);
                return 64;
            }
        }

        Path tracesRoot = root;
        List<Path> traceFiles = findTraceFiles(tracesRoot);
        if (traceFiles.isEmpty()) {
            String msg = "leaf-coverage: no trace files found under " + tracesRoot
                + "/**/target/leaf-coverage.jsonl. Run `mvn -Pleaf-coverage verify` to populate them.";
            if (verify) {
                System.err.println(msg + " Skipping verify.");
                return 0;
            }
            System.err.println(msg);
            return 1;
        }

        Path modelDir = root.resolve("graphitron/src/main/java/no/sikt/graphitron/rewrite/model");
        List<Leaf> leaves = parseLeaves(modelDir);
        Path roadmapDir = root.resolve("roadmap");
        List<Mention> mentions = parseMentions(roadmapDir, leaves);

        // Default output paths. Internal report lands next to the roadmap; migration fragment
        // lands directly in the docs site tree so the asciidoctor build picks it up via
        // include:: without an additional copy step. The root-dir argument points at
        // graphitron-rewrite/, so the migration fragment goes one level up under docs/.
        Path outFile = outputOverride != null ? outputOverride
            : (migration
                ? root.resolveSibling("docs/manual/_generated/supported-schema-shapes.adoc")
                : roadmapDir.resolve("inference-axis-coverage.adoc"));

        String rendered;
        try {
            rendered = render(traceFiles, leaves, mentions, migration);
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB failure: " + e.getMessage(), e);
        }

        if (verify) {
            String existing = Files.exists(outFile) ? Files.readString(outFile) : "";
            if (!existing.equals(rendered)) {
                System.err.println("leaf-coverage report at " + outFile + " is out of date. Regenerate with:");
                // Portable form: regenerate from the repo root; the trailing positional uses the
                // project-relative path the contributor would type instead of the absolute one
                // resolved at runtime, so CI logs and local error prints read the same.
                System.err.println("  mvn -f graphitron-rewrite/pom.xml -pl roadmap-tool exec:java"
                    + " -Dexec.args='leaf-coverage graphitron-rewrite"
                    + (migration ? " --mode=migration" : "") + "'");
                return 1;
            }
            System.out.println("leaf-coverage report is up to date: " + outFile);
            return 0;
        }
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, rendered);
        System.out.println("wrote " + outFile);
        return 0;
    }

    private static List<Path> findTraceFiles(Path root) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.endsWith("leaf-coverage.jsonl")
                        && file.getParent() != null
                        && file.getParent().getFileName().toString().equals("target")) {
                    out.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                if (name.equals(".git") || name.equals("node_modules")) return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    /** A terminal record in the sealed leaf hierarchy plus its one-line intent javadoc. */
    record Leaf(String hierarchy, String simpleName, String fqn, String intent) {}

    /** A roadmap mention of a leaf class name, by item ID. */
    record Mention(String leafSimpleName, String roadmapId) {}

    /**
     * Parses the five named hierarchy source files and walks the sealed permits chain
     * from each root, collecting every terminal record. Records nested under intermediate
     * sealed-grouping interfaces (e.g. {@code ChildField.TableTargetField}) are still
     * emitted under the root hierarchy column.
     */
    static List<Leaf> parseLeaves(Path modelDir) throws IOException {
        // Parse every .java in the model directory so QueryField / MutationField (declared in
        // their own files, permitted by RootField) and similar split-file leaves are reachable.
        // Each parsed file's sealed types and records key by simple name; walkPermits resolves
        // a permit token by simple name across all parsed files.
        Map<String, ParsedFile> files = new LinkedHashMap<>();
        try (var stream = Files.list(modelDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
                files.put(simple, parseSourceFile(Files.readString(file)));
            }
        }
        List<Leaf> out = new ArrayList<>();
        java.util.Set<String> emittedFqns = new java.util.HashSet<>();
        for (String h : HIERARCHIES) {
            ParsedFile pf = files.get(h);
            if (pf == null) continue;
            // Each top-level hierarchy roots its own walk. Stop the descent when we'd cross
            // into another top-level hierarchy (so RootField / ChildField / InputField each
            // get their own leaves; GraphitronField's only direct leaf is UnclassifiedField).
            walkPermits(h, h, h, pf, files, out, emittedFqns);
        }
        out.sort((a, b) -> {
            int byH = Integer.compare(HIERARCHIES.indexOf(a.hierarchy()), HIERARCHIES.indexOf(b.hierarchy()));
            return byH != 0 ? byH : a.simpleName().compareTo(b.simpleName());
        });
        return out;
    }

    private static void walkPermits(String hierarchy, String containerSimple, String immediateSealedParent,
            ParsedFile pf, Map<String, ParsedFile> allFiles, List<Leaf> out,
            java.util.Set<String> emittedFqns) {
        Sealed sealed = pf.sealedTypes().get(containerSimple);
        if (sealed == null) {
            Record rec = pf.records().get(containerSimple);
            if (rec != null) {
                // FQN form mirrors what the trace emits, which is the *Java enclosing class*
                // (Class.getEnclosingClass), not the sealed-parent in the permits chain. For
                // records whose Java enclosing class differs from their sealed parent (e.g. DML
                // mutation arms declared inside MutationField but permitted by inner DmlTableField),
                // the trace uses the enclosing class. immediateSealedParent stays the fallback for
                // legacy parses where the brace tracker found no enclosing class.
                String enclosing = rec.enclosingClass() == null || rec.enclosingClass().isEmpty()
                    ? immediateSealedParent
                    : rec.enclosingClass();
                String fqn = enclosing + "." + containerSimple;
                if (emittedFqns.add(fqn)) {
                    out.add(new Leaf(hierarchy, containerSimple, fqn, rec.intent()));
                }
            }
            return;
        }
        for (String permit : sealed.permits()) {
            String simple = simpleName(permit);
            // If the permit names another top-level hierarchy (e.g. GraphitronField permits
            // RootField), don't recurse — that hierarchy walks itself separately.
            if (HIERARCHIES.contains(simple) && !simple.equals(hierarchy)) continue;
            ParsedFile target = pf;
            if (allFiles.containsKey(simple)) {
                target = allFiles.get(simple);
            }
            if (target.sealedTypes().containsKey(simple)) {
                // Descending into a nested sealed grouping: it becomes the new immediate parent.
                walkPermits(hierarchy, simple, simple, target, allFiles, out, emittedFqns);
            } else if (target.records().containsKey(simple)) {
                walkPermits(hierarchy, simple, containerSimple, target, allFiles, out, emittedFqns);
            } else if (pf.sealedTypes().containsKey(simple)) {
                walkPermits(hierarchy, simple, simple, pf, allFiles, out, emittedFqns);
            } else if (pf.records().containsKey(simple)) {
                walkPermits(hierarchy, simple, containerSimple, pf, allFiles, out, emittedFqns);
            }
        }
    }

    private static String simpleName(String dotted) {
        int dot = dotted.lastIndexOf('.');
        return dot < 0 ? dotted : dotted.substring(dot + 1);
    }

    static List<Mention> parseMentions(Path roadmapDir, List<Leaf> leaves) throws IOException {
        if (!Files.isDirectory(roadmapDir)) return List.of();
        List<Mention> out = new ArrayList<>();
        var idPattern = Pattern.compile("(?m)^id:\\s*(R\\d+)\\s*$");
        try (var stream = Files.list(roadmapDir)) {
            for (Path file : stream.filter(p -> p.getFileName().toString().endsWith(".md")).toList()) {
                String content = Files.readString(file);
                Matcher m = idPattern.matcher(content);
                if (!m.find()) continue;
                String id = m.group(1);
                for (Leaf leaf : leaves) {
                    // Match by simple name (ChildField.TableField) or by short form (TableField).
                    if (content.contains(leaf.fqn()) || mentionsBare(content, leaf.simpleName())) {
                        out.add(new Mention(leaf.simpleName(), id));
                    }
                }
            }
        }
        return out;
    }

    /** Whitespace-bounded match avoids false positives like "FieldType" matching "Field". */
    private static boolean mentionsBare(String content, String simpleName) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(simpleName) + "\\b");
        return p.matcher(content).find();
    }

    static String render(List<Path> traceFiles, List<Leaf> leaves, List<Mention> mentions,
            boolean migration) throws SQLException {
        // Explicit registration: the exec-maven-plugin's plugin-classloader sometimes does not
        // surface ServiceLoader-discovered drivers from the project's runtime classpath, so
        // DriverManager.getConnection alone fails with "No suitable driver". The forName call
        // is a no-op when ServiceLoader has already registered the driver.
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB driver not on classpath", e);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            stageLeaves(conn, leaves);
            stageMentions(conn, mentions);
            stageTrace(conn, traceFiles);
            return migration ? renderMigration(conn) : renderInternal(conn);
        }
    }

    private static void stageLeaves(Connection conn, List<Leaf> leaves) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE leaves (hierarchy VARCHAR, leaf VARCHAR, fqn VARCHAR, intent VARCHAR)");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO leaves VALUES (?, ?, ?, ?)")) {
            for (Leaf l : leaves) {
                ps.setString(1, l.hierarchy());
                ps.setString(2, l.simpleName());
                ps.setString(3, l.fqn());
                ps.setString(4, l.intent() == null ? "" : l.intent());
                ps.execute();
            }
        }
    }

    private static void stageMentions(Connection conn, List<Mention> mentions) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE mentions (leaf VARCHAR, roadmap_id VARCHAR)");
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO mentions VALUES (?, ?)")) {
            for (Mention m : mentions) {
                ps.setString(1, m.leafSimpleName());
                ps.setString(2, m.roadmapId());
                ps.execute();
            }
        }
    }

    private static void stageTrace(Connection conn, List<Path> traceFiles) throws SQLException {
        // DuckDB read_json_auto accepts a list literal; pass an explicit array of paths to keep
        // the glob behaviour predictable on platforms where path globbing differs.
        StringBuilder paths = new StringBuilder("[");
        for (int i = 0; i < traceFiles.size(); i++) {
            if (i > 0) paths.append(", ");
            paths.append("'").append(traceFiles.get(i).toString().replace("'", "''")).append("'");
        }
        paths.append("]");
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE VIEW trace AS SELECT * FROM read_json_auto(" + paths
                + ", union_by_name = true)");
        }
    }

    /**
     * Internal report: one row per leaf with hierarchy, leaf, intent, classify count, tier,
     * cross-cutting flag, fixture count, test classes, and roadmap mentions.
     */
    private static String renderInternal(Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("= Inference-axis coverage report\n");
        sb.append(":description: One row per sealed leaf in GraphitronField, RootField, ChildField, InputField, GraphitronType. ")
          .append("Generated by `graphitron-roadmap-tool leaf-coverage`. Regenerate with `mvn -Pleaf-coverage verify` then ")
          .append("`mvn -pl :graphitron-roadmap-tool exec:java -Dexec.args='leaf-coverage graphitron-rewrite'`. Never edit by hand.\n\n");
        sb.append("Triage (Covered / Trivial gap / RC-blocker / Defer) and gap-closure live in a follow-up roadmap item ")
          .append("that depends on this one; this table is the regenerable data the triage reads from.\n\n");

        for (String hierarchy : HIERARCHIES) {
            String sql = """
                SELECT
                    l.leaf,
                    l.fqn,
                    l.intent,
                    COALESCE(t.classify_count, 0) AS classify_count,
                    t.fixtures,
                    t.tier,
                    COALESCE(t.cross_cutting, FALSE) AS cross_cutting,
                    t.test_classes,
                    m.roadmap_ids
                FROM leaves l
                LEFT JOIN (
                    SELECT
                        leaf,
                        COUNT(*) AS classify_count,
                        COUNT(DISTINCT NULLIF(source, '')) AS fixtures,
                        CASE MAX(CASE tier
                            WHEN 'unit'        THEN 1
                            WHEN 'pipeline'    THEN 2
                            WHEN 'compilation' THEN 3
                            WHEN 'execution'   THEN 4
                            ELSE NULL
                        END)
                            WHEN 1 THEN 'unit'
                            WHEN 2 THEN 'pipeline'
                            WHEN 3 THEN 'compilation'
                            WHEN 4 THEN 'execution'
                            ELSE NULL
                        END AS tier,
                        BOOL_OR(tier = 'cross-cutting') AS cross_cutting,
                        STRING_AGG(DISTINCT NULLIF(test, ''), ', ' ORDER BY NULLIF(test, '')) AS test_classes
                    FROM trace
                    WHERE op = 'classify'
                    GROUP BY leaf
                ) t ON t.leaf = l.fqn
                LEFT JOIN (
                    SELECT leaf, STRING_AGG(DISTINCT roadmap_id, ', ' ORDER BY roadmap_id) AS roadmap_ids
                    FROM mentions GROUP BY leaf
                ) m ON m.leaf = l.leaf
                WHERE l.hierarchy = ?
                ORDER BY l.leaf
                """;

            sb.append("== ").append(hierarchy).append("\n\n");
            sb.append("[cols=\"2,4,1,1,1,1,3,2\", options=\"header\"]\n");
            sb.append("|===\n");
            sb.append("| Leaf | Intent | Traces | Tier | X-cut | Fixtures | Tests | Roadmap\n");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hierarchy);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String leaf = rs.getString("leaf");
                        String intent = rs.getString("intent");
                        long count = rs.getLong("classify_count");
                        Long fixtures = (Long) rs.getObject("fixtures");
                        String tier = rs.getString("tier");
                        boolean crossCut = rs.getBoolean("cross_cutting");
                        String tests = rs.getString("test_classes");
                        String mentionsCol = rs.getString("roadmap_ids");
                        sb.append("| `").append(leaf).append("`\n");
                        sb.append("| ").append(intent == null || intent.isEmpty() ? "_(no javadoc)_" : escapeAdocCell(intent)).append("\n");
                        sb.append("| ").append(count == 0 ? "0" : Long.toString(count)).append("\n");
                        sb.append("| ").append(tier == null ? "-" : tier).append("\n");
                        sb.append("| ").append(crossCut ? "yes" : "no").append("\n");
                        sb.append("| ").append(fixtures == null ? "-" : fixtures.toString()).append("\n");
                        sb.append("| ").append(tests == null ? "-" : escapeAdocCell(shortenTestList(tests))).append("\n");
                        sb.append("| ").append(mentionsCol == null ? "-" : mentionsCol).append("\n");
                    }
                }
            }
            sb.append("|===\n\n");
        }

        sb.append("== Reading guide\n\n");
        sb.append("- *Traces*: count of `classify` records in the per-module JSONL traces. ")
          .append("Zero means no test exercised this leaf during the most recent ")
          .append("`mvn -Pleaf-coverage verify` run; the leaf may still be reachable, just not by any covered fixture.\n");
        sb.append("- *Tier*: highest tier observed over `unit < pipeline < compilation < execution`. ")
          .append("`-` means classification ran outside any tagged test (e.g. a real generator run); ")
          .append("such records still count as classification-tier coverage.\n");
        sb.append("- *X-cut*: `yes` when at least one record carries `tier=cross-cutting`. ")
          .append("Independent of the four-arm tier ordering; a leaf exercised only by ")
          .append("cross-cutting tests reads as `tier: -, X-cut: yes`.\n");
        sb.append("- *Fixtures*: distinct non-empty `source` values; counts how many SDL fixtures ")
          .append("the leaf appears in. `TestSchemaHelper` inline-string fixtures emit empty ")
          .append("`source` and do not increment this column; the leaf still appears in the trace.\n");
        sb.append("- *Roadmap*: roadmap items that mention the leaf class name. Useful for tracing ")
          .append("leaves with deferred or in-flight follow-up work.\n");
        return sb.toString();
    }

    /** Compact noisy STRING_AGG output for the report cell. */
    private static String shortenTestList(String tests) {
        // Drop the common prefix "no.sikt.graphitron.rewrite." for readability.
        return tests.replace("no.sikt.graphitron.rewrite.", "");
    }

    /**
     * Migration-guide fragment: one bullet per supported leaf, consumer-facing wording.
     * No internal columns (trace count, tier).
     */
    private static String renderMigration(Connection conn) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("// Generated by `graphitron-roadmap-tool leaf-coverage --mode=migration`.\n");
        sb.append("// Regenerate via the verify-mode CI guard. Never edit by hand.\n\n");
        sb.append("Supported schema shapes are enumerated below by output sealed-leaf class. ")
          .append("Each supported entry has at least one execution-tier or pipeline-tier fixture in the rewrite test suite.\n\n");
        for (String hierarchy : HIERARCHIES) {
            String sql = """
                SELECT
                    l.leaf,
                    l.fqn,
                    l.intent,
                    COALESCE(t.cnt, 0) > 0 AS observed
                FROM leaves l
                LEFT JOIN (
                    SELECT leaf, COUNT(*) AS cnt
                    FROM trace
                    WHERE op = 'classify'
                    GROUP BY leaf
                ) t ON t.leaf = l.fqn
                WHERE l.hierarchy = ?
                ORDER BY l.leaf
                """;
            sb.append("=== ").append(hierarchy).append("\n\n");
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, hierarchy);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean observed = rs.getBoolean("observed");
                        String leaf = rs.getString("leaf");
                        String intent = rs.getString("intent");
                        sb.append(observed ? "* *" : "* _(not yet supported)_ *").append(leaf).append("*");
                        if (intent != null && !intent.isEmpty()) {
                            sb.append(": ").append(intent);
                        }
                        sb.append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String escapeAdocCell(String s) {
        return s.replace("|", "\\|");
    }

    // ===== Sealed permits parser =====

    record ParsedFile(Map<String, Sealed> sealedTypes, Map<String, Record> records) {}
    record Sealed(String simpleName, List<String> permits) {}
    /**
     * A terminal record. {@code enclosingClass} is the actual Java enclosing class (the type
     * whose body the record is declared in), which the classifier-side {@code leafName} helper
     * derives via {@link Class#getEnclosingClass()}. The walker pairs this with the simple name
     * to produce the FQN that matches trace records.
     */
    record Record(String simpleName, String enclosingClass, String intent) {}

    private static final Pattern SEALED_PATTERN = Pattern.compile(
        "(?:public\\s+)?sealed\\s+(?:interface|class)\\s+(\\w+)"
        + "(?:\\s+(?:extends|implements)[^{]+?)?"
        + "\\s+permits\\s+([^{]+?)\\{",
        Pattern.DOTALL);

    /** Matches both {@code record Foo(...)} and {@code record Foo<T>(...)}. */
    private static final Pattern RECORD_PATTERN = Pattern.compile(
        "(?:public\\s+|private\\s+|static\\s+|final\\s+)*record\\s+(\\w+)\\s*(?:<[^>]+>\\s*)?\\(",
        Pattern.DOTALL);

    /** Matches enclosing-type openers we need to push on the stack to compute enclosingClass. */
    private static final Pattern TYPE_OPENER = Pattern.compile(
        "(?:public\\s+|private\\s+|static\\s+|final\\s+|sealed\\s+|non-sealed\\s+|abstract\\s+)*"
            + "(?:interface|class|enum|record)\\s+(\\w+)",
        Pattern.DOTALL);

    /**
     * Pulls the first prose line out of the javadoc block immediately preceding {@code offset}.
     * "Immediately" means: between the javadoc's closing &#42;/ marker and {@code offset} there
     * are only whitespace, annotations, or line comments. If a substantive declaration line
     * intervenes (modifiers, another type opener, a brace, etc.), the javadoc is not the
     * declaration's own; return empty rather than stealing the parent's.
     */
    private static String preceedingJavadoc(String content, int offset) {
        int end = content.lastIndexOf("*/", offset);
        if (end < 0) return "";
        int start = content.lastIndexOf("/**", end);
        if (start < 0) return "";
        String between = content.substring(end + 2, offset);
        for (String raw : between.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("@") && !line.contains(" ")) continue;   // bare annotation
            if (line.startsWith("@")) continue;                           // annotation with args
            if (line.startsWith("//")) continue;                          // line comment
            // Anything else means another decl sits between the javadoc and this offset; the
            // javadoc owns the closer one. The current record has no javadoc of its own.
            return "";
        }
        String javadoc = content.substring(start + 3, end);
        for (String raw : javadoc.split("\n")) {
            String line = raw.strip();
            if (line.startsWith("*")) line = line.substring(1).strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("@")) break;
            if (line.endsWith(".")) line = line.substring(0, line.length() - 1);
            return cleanJavadocInline(line);
        }
        return "";
    }

    /** Strips Javadoc inline tags that confuse AsciiDoc rendering. */
    private static String cleanJavadocInline(String line) {
        // {@code X} → `X`. {@link X} / {@linkplain X} → X. Strip the wrapping braces only;
        // AsciiDoc handles backticks for code spans.
        line = line.replaceAll("\\{@code\\s+([^}]+)\\}", "`$1`");
        line = line.replaceAll("\\{@link(?:plain)?\\s+([^}]+)\\}", "`$1`");
        return line;
    }

    private static ParsedFile parseSourceFile(String content) {
        Map<String, Sealed> sealedTypes = new LinkedHashMap<>();
        Map<String, Record> records = new LinkedHashMap<>();
        Matcher sm = SEALED_PATTERN.matcher(content);
        while (sm.find()) {
            String name = sm.group(1);
            String permitsRaw = sm.group(2);
            List<String> permits = new ArrayList<>();
            for (String tok : permitsRaw.split(",")) {
                String t = tok.strip();
                if (!t.isEmpty()) permits.add(t);
            }
            sealedTypes.put(name, new Sealed(name, permits));
        }
        // Brace-tracking pass to associate each record with its enclosing class. Skips strings,
        // chars, line comments, and block comments so a `{` inside a string literal doesn't
        // confuse the depth counter.
        java.util.Deque<String> typeStack = new java.util.ArrayDeque<>();
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            // Block comment
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '*') {
                int end = content.indexOf("*/", i + 2);
                i = end < 0 ? content.length() : end + 2;
                continue;
            }
            // Line comment
            if (c == '/' && i + 1 < content.length() && content.charAt(i + 1) == '/') {
                int end = content.indexOf('\n', i + 2);
                i = end < 0 ? content.length() : end + 1;
                continue;
            }
            // String literal
            if (c == '"') {
                if (i + 2 < content.length() && content.charAt(i + 1) == '"' && content.charAt(i + 2) == '"') {
                    int end = content.indexOf("\"\"\"", i + 3);
                    i = end < 0 ? content.length() : end + 3;
                } else {
                    int j = i + 1;
                    while (j < content.length() && content.charAt(j) != '"') {
                        if (content.charAt(j) == '\\' && j + 1 < content.length()) j += 2;
                        else j++;
                    }
                    i = j + 1;
                }
                continue;
            }
            // Char literal
            if (c == '\'') {
                int j = i + 1;
                while (j < content.length() && content.charAt(j) != '\'') {
                    if (content.charAt(j) == '\\' && j + 1 < content.length()) j += 2;
                    else j++;
                }
                i = j + 1;
                continue;
            }
            // Type opener: scan for keyword + identifier ahead of `{`. Use the smaller TYPE_OPENER
            // to find the simple name; ensure the next `{` is the body brace before pushing.
            if (Character.isLetter(c) || c == '_') {
                Matcher topener = TYPE_OPENER.matcher(content);
                if (topener.find(i) && topener.start() == i) {
                    String simpleName = topener.group(1);
                    int after = topener.end();
                    int braceIdx = -1;
                    int parenDepth = 0, angleDepth = 0;
                    for (int j = after; j < content.length(); j++) {
                        char d = content.charAt(j);
                        if (d == '/' && j + 1 < content.length() && content.charAt(j + 1) == '*') {
                            int e = content.indexOf("*/", j + 2);
                            j = e < 0 ? content.length() - 1 : e + 1;
                            continue;
                        }
                        if (d == '/' && j + 1 < content.length() && content.charAt(j + 1) == '/') {
                            int e = content.indexOf('\n', j + 2);
                            j = e < 0 ? content.length() - 1 : e;
                            continue;
                        }
                        if (d == '(') parenDepth++;
                        else if (d == ')') parenDepth--;
                        else if (d == '<') angleDepth++;
                        else if (d == '>' && angleDepth > 0) angleDepth--;
                        else if (d == ';' && parenDepth == 0 && angleDepth == 0) {
                            // Forward declaration / abstract method-ish. No body.
                            braceIdx = -1;
                            i = j + 1;
                            break;
                        }
                        else if (d == '{' && parenDepth == 0 && angleDepth == 0) {
                            braceIdx = j;
                            break;
                        }
                    }
                    if (braceIdx >= 0) {
                        // Body opens here. Capture record entry first (with current top-of-stack
                        // as enclosing class), then push.
                        Matcher rm = RECORD_PATTERN.matcher(content);
                        if (rm.find(i) && rm.start() == i) {
                            String enclosing = "";
                            for (String t : typeStack) {
                                if (!t.isEmpty()) { enclosing = t; break; }
                            }
                            String intent = preceedingJavadoc(content, i);
                            records.put(simpleName, new Record(simpleName, enclosing, intent));
                        }
                        typeStack.push(simpleName);
                        i = braceIdx + 1;
                        continue;
                    } else if (i + 0 < content.length() && content.charAt(i) != '{' && braceIdx < 0) {
                        // Fall through past the matched declaration without pushing.
                        i = after;
                        continue;
                    }
                }
            }
            if (c == '{') {
                // Non-type brace (method body, initialiser, lambda, etc.). Push a sentinel so
                // the closing `}` is balanced without affecting the type stack lookup.
                typeStack.push("");
                i++;
                continue;
            }
            if (c == '}') {
                if (!typeStack.isEmpty()) typeStack.pop();
                i++;
                continue;
            }
            i++;
        }
        return new ParsedFile(sealedTypes, records);
    }
}
