package no.sikt.graphitron.roadmap;

import java.io.IOException;
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

/**
 * Post-processor that aggregates the per-module JaCoCo CSVs (emitted by the opt-in
 * {@code coverage} profile's {@code report} goal at {@code target/site/jacoco/jacoco.csv})
 * into one AsciiDoc page, {@code roadmap/source-coverage.adoc}.
 *
 * <p>Complementary to {@link LeafCoverageReport}: that report measures which
 * classification-taxonomy leaves the corpus demonstrates, this one measures which source
 * lines and branches execute. The leaf-join table renders both facts side by side, one row
 * per sealed leaf, which is the view neither report can produce alone.
 *
 * <p>Backed by embedded DuckDB, following {@link LeafCoverageReport}'s shape: the connection
 * is in-memory and ephemeral. Open, register the CSVs as views via {@code read_csv_auto}
 * (one view for the combined reports, one per tier, with the tier name taken from the
 * {@code jacoco-<tier>} directory suffix), stage the small parsed {@code leaves} table and
 * the classifier-trace view, run the aggregation queries, render, close. No persisted
 * {@code .duckdb} file.
 *
 * <p>Java-to-SQL vocabulary: a {@code row} is one JaCoCo CSV line, keyed on
 * {@code (module, pkg, cls)}; {@code module} is the pom {@code <name>} coordinate with the
 * groupId stripped, {@code pkg} the Java package, {@code cls} the class name with nested
 * separators normalised from {@code $} to {@code .} so it matches the leaf FQNs
 * {@link LeafCoverageReport#parseLeaves} produces.
 *
 * <p>No verify mode, deliberately: a coverage percentage is a function of an executed run and
 * its environment, so unlike the leaf-coverage and directive-support pages there is no fixed
 * point a drift gate could compare against. For the same reason no
 * {@code roadmap/source-coverage.adoc} is committed; {@code render-adoc} synthesizes a stub
 * when the file is absent.
 */
final class SourceCoverageReport {

    /** The package holding the sealed leaf hierarchies the leaf-join table keys on. */
    static final String MODEL_PACKAGE = "no.sikt.graphitron.rewrite.model";

    /** The module whose per-package and per-class tables answer the pyramid question. */
    static final String GENERATOR_MODULE = "graphitron";

    /** Stated in the page prose so a bounded list is never mistaken for the whole story. */
    static final int TOP_MISSED_CLASSES = 25;

    private SourceCoverageReport() {}

    /**
     * Entry point invoked by {@link Main}. One argument, the repository root; the CSV globs,
     * the model sources, the trace files, and the output path all resolve against it.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: source-coverage <root-dir>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }

        List<Path> combined = new ArrayList<>();
        Map<String, List<Path>> tiered = new TreeMap<>(TierVocabulary.tierOrder());
        findCsvFiles(root, combined, tiered);
        if (combined.isEmpty()) {
            System.err.println("source-coverage: no jacoco.csv found under " + root
                + "/**/target/site/jacoco/. Run `mvn verify -Plocal-db -Pcoverage` to produce"
                + " coverage data, then rerun this command.");
            throw new BuildFailure("no coverage CSVs");
        }

        Path modelDir = root.resolve("graphitron/src/main/java/no/sikt/graphitron/rewrite/model");
        List<LeafCoverageReport.Leaf> leaves = LeafCoverageReport.parseLeaves(modelDir);
        List<Path> traceFiles = LeafCoverageReport.findTraceFiles(root);

        String rendered;
        try {
            rendered = render(combined, tiered, leaves, traceFiles);
        } catch (SQLException e) {
            throw new RuntimeException("DuckDB failure: " + e.getMessage(), e);
        }

        Path outFile = root.resolve("roadmap/source-coverage.adoc");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, rendered);
        System.out.println("wrote " + outFile);
        return 0;
    }

    /**
     * Collects {@code **}{@code /target/site/jacoco/jacoco.csv} into {@code combined} and
     * {@code **}{@code /target/site/jacoco-<tier>/jacoco.csv} into {@code tiered}, keyed by
     * the directory suffix.
     */
    static void findCsvFiles(Path root, List<Path> combined, Map<String, List<Path>> tiered)
            throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                Path parent = file.getParent();
                if (!file.getFileName().toString().equals("jacoco.csv") || parent == null) {
                    return FileVisitResult.CONTINUE;
                }
                Path site = parent.getParent();
                if (site == null || !site.getFileName().toString().equals("site")
                        || site.getParent() == null
                        || !site.getParent().getFileName().toString().equals("target")) {
                    return FileVisitResult.CONTINUE;
                }
                String dir = parent.getFileName().toString();
                if (dir.equals("jacoco")) {
                    combined.add(file);
                } else if (dir.startsWith("jacoco-") && dir.length() > "jacoco-".length()) {
                    tiered.computeIfAbsent(dir.substring("jacoco-".length()), t -> new ArrayList<>())
                        .add(file);
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
    }

    static String render(List<Path> combined, Map<String, List<Path>> tiered,
            List<LeafCoverageReport.Leaf> leaves, List<Path> traceFiles) throws SQLException {
        try {
            Class.forName("org.duckdb.DuckDBDriver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("DuckDB driver not on classpath", e);
        }
        try (Connection conn = DriverManager.getConnection("jdbc:duckdb:")) {
            stageCsv(conn, "combined", combined);
            for (Map.Entry<String, List<Path>> tier : tiered.entrySet()) {
                stageCsv(conn, tierView(tier.getKey()), tier.getValue());
            }
            LeafCoverageReport.stageLeaves(conn, leaves);
            stageTraceOrEmpty(conn, traceFiles);
            return renderPage(conn, List.copyOf(tiered.keySet()), leaves, !traceFiles.isEmpty());
        }
    }

    /** One DuckDB view per CSV set; tier names are validated identifiers before interpolation. */
    private static String tierView(String tier) {
        if (!tier.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("unexpected tier directory suffix: " + tier);
        }
        return "tier_" + tier.replace('-', '_');
    }

    private static void stageCsv(Connection conn, String view, List<Path> files)
            throws SQLException {
        StringBuilder paths = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) paths.append(", ");
            paths.append("'").append(files.get(i).toString().replace("'", "''")).append("'");
        }
        paths.append("]");
        // Aliased once at staging so every query downstream reads the SQL-shaped names. GROUP is
        // an SQL keyword, hence the quoting; the $-to-. normalisation makes nested-class names
        // comparable with the leaf FQNs.
        String sql = "CREATE VIEW " + view + " AS SELECT"
            + " regexp_replace(\"GROUP\", '^[^:]*:', '') AS module,"
            + " \"PACKAGE\" AS pkg,"
            + " replace(\"CLASS\", '$', '.') AS cls,"
            + " \"LINE_MISSED\" AS line_missed, \"LINE_COVERED\" AS line_covered,"
            + " \"BRANCH_MISSED\" AS branch_missed, \"BRANCH_COVERED\" AS branch_covered,"
            + " \"METHOD_MISSED\" AS method_missed, \"METHOD_COVERED\" AS method_covered"
            + " FROM read_csv_auto(" + paths + ", header = true)";
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * The leaf join reads the same classifier traces {@link LeafCoverageReport} reads. When a
     * build ran with {@code -Dleaf-coverage.skip} there are none; an empty relation keeps the
     * queries valid and the render notes that trace counts are unavailable rather than zero.
     */
    private static void stageTraceOrEmpty(Connection conn, List<Path> traceFiles) throws SQLException {
        if (traceFiles.isEmpty()) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE trace (leaf VARCHAR, op VARCHAR)");
            }
            return;
        }
        LeafCoverageReport.stageTrace(conn, traceFiles);
    }

    private static String renderPage(Connection conn, List<String> tiers,
            List<LeafCoverageReport.Leaf> leaves, boolean tracesPresent) throws SQLException {
        StringBuilder sb = new StringBuilder();
        sb.append("= Source coverage report\n");
        sb.append(":description: JaCoCo line/branch/method coverage per module, package, and class, ")
          .append("joined with the classification-leaf traces. ")
          .append("Generated by `graphitron-roadmap-tool source-coverage`. Never edit by hand.\n\n");

        sb.append("Each module's report attributes only that module's own classes, from that ")
          .append("module's test forks under `-Pcoverage`. `graphitron`'s figures are ")
          .append("generator-source coverage from its unit and pipeline tiers; ")
          .append("`graphitron-sakila-example`'s figures are coverage of _generated_ code from ")
          .append("the compilation and execution tiers. CI regenerates this page on every trunk ")
          .append("push. Regenerate locally with `mvn verify -Plocal-db -Pcoverage`, then ")
          .append("`mvn -pl roadmap-tool exec:java -q -Dexec.args='source-coverage .'`.\n\n");

        sb.append("Two kinds of generator code are missing from every figure below, for two ")
          .append("different reasons. Generator code that runs in the Maven JVM during ")
          .append("`graphitron:generate` is never instrumented at all: the agent attaches to ")
          .append("test forks only, so those executions are uncollected. Generator code that ")
          .append("runs in another module's test JVM (`GeneratorDeterminismTest` invokes the ")
          .append("generator in-process in `graphitron-sakila-example`) _is_ collected into ")
          .append("that module's exec data, but discarded at report time, because a module's ")
          .append("report analyses only its own classes.\n\n");

        if (!tiers.isEmpty()) {
            sb.append("Read the per-tier columns as slices, not as a decomposition: each column ")
              .append("covers only the classes its `-Dgroups=<tier>` run selected, `")
              .append(TierVocabulary.CROSS_CUTTING)
              .append("` classes fall into none of them, and the slices do not sum to the ")
              .append("combined figure. Renderer arm tests, a unit-tier family the tier guide ")
              .append("endorses for per-arm structural assertions, land in the `unit` column by ")
              .append("design; a raw unit-versus-pipeline comparison attributes that deliberate ")
              .append("unit testing to the arm the doctrine is skeptical of.\n\n");
        }

        renderModuleTable(conn, sb);
        renderPackageTable(conn, sb, tiers);
        renderTopMissedTable(conn, sb);
        renderLeafJoinTable(conn, sb, leaves, tracesPresent);
        return sb.toString();
    }

    private static void renderModuleTable(Connection conn, StringBuilder sb) throws SQLException {
        sb.append("== Coverage by module\n\n");
        sb.append("[cols=\"3,1,1,1\", options=\"header\"]\n|===\n");
        sb.append("| Module | Line | Branch | Method\n");
        String sql = """
            SELECT module,
                   SUM(line_missed) AS lm, SUM(line_covered) AS lc,
                   SUM(branch_missed) AS bm, SUM(branch_covered) AS bc,
                   SUM(method_missed) AS mm, SUM(method_covered) AS mc
            FROM combined
            GROUP BY module
            ORDER BY module
            """;
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                sb.append("| `").append(rs.getString("module")).append("`\n");
                sb.append("| ").append(pct(rs.getLong("lc"), rs.getLong("lm"))).append("\n");
                sb.append("| ").append(pct(rs.getLong("bc"), rs.getLong("bm"))).append("\n");
                sb.append("| ").append(pct(rs.getLong("mc"), rs.getLong("mm"))).append("\n");
            }
        }
        sb.append("|===\n\n");
    }

    private static void renderPackageTable(Connection conn, StringBuilder sb, List<String> tiers)
            throws SQLException {
        sb.append("== `").append(GENERATOR_MODULE).append("` by package\n\n");
        sb.append("Package names are shortened by dropping the `no.sikt.graphitron.` prefix.");
        if (!tiers.isEmpty()) {
            sb.append(" The per-tier columns are line coverage from that tier's run alone.");
        }
        sb.append("\n\n");
        sb.append("[cols=\"4,1,1,1").append(",1".repeat(tiers.size())).append("\", options=\"header\"]\n|===\n");
        sb.append("| Package | Line | Branch | Method");
        for (String tier : tiers) {
            sb.append(" | ").append(tier).append(" line");
        }
        sb.append("\n");

        // Per-tier line coverage keyed by package, collected up front so the row loop below
        // stays a straight merge.
        Map<String, Map<String, String>> tierPct = new LinkedHashMap<>();
        for (String tier : tiers) {
            Map<String, String> byPkg = new LinkedHashMap<>();
            String sql = "SELECT pkg, SUM(line_missed) AS lm, SUM(line_covered) AS lc FROM "
                + tierView(tier) + " WHERE module = ? GROUP BY pkg";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, GENERATOR_MODULE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byPkg.put(rs.getString("pkg"), pct(rs.getLong("lc"), rs.getLong("lm")));
                    }
                }
            }
            tierPct.put(tier, byPkg);
        }

        String sql = """
            SELECT pkg,
                   SUM(line_missed) AS lm, SUM(line_covered) AS lc,
                   SUM(branch_missed) AS bm, SUM(branch_covered) AS bc,
                   SUM(method_missed) AS mm, SUM(method_covered) AS mc
            FROM combined
            WHERE module = ?
            GROUP BY pkg
            ORDER BY pkg
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, GENERATOR_MODULE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String pkg = rs.getString("pkg");
                    sb.append("| `").append(shortenPackage(pkg)).append("`\n");
                    sb.append("| ").append(pct(rs.getLong("lc"), rs.getLong("lm"))).append("\n");
                    sb.append("| ").append(pct(rs.getLong("bc"), rs.getLong("bm"))).append("\n");
                    sb.append("| ").append(pct(rs.getLong("mc"), rs.getLong("mm"))).append("\n");
                    for (String tier : tiers) {
                        sb.append("| ").append(tierPct.get(tier).getOrDefault(pkg, "-")).append("\n");
                    }
                }
            }
        }
        sb.append("|===\n\n");
    }

    private static void renderTopMissedTable(Connection conn, StringBuilder sb) throws SQLException {
        sb.append("== `").append(GENERATOR_MODULE).append("` classes with the most missed lines\n\n");
        sb.append("The ").append(TOP_MISSED_CLASSES).append(" classes with the most missed lines. ")
          .append("A bounded list, not the whole story; the package table above is complete.\n\n");
        sb.append("[cols=\"5,1,1\", options=\"header\"]\n|===\n");
        sb.append("| Class | Missed lines | Line\n");
        String sql = """
            SELECT pkg, cls, SUM(line_missed) AS lm, SUM(line_covered) AS lc
            FROM combined
            WHERE module = ?
            GROUP BY pkg, cls
            ORDER BY lm DESC, pkg, cls
            LIMIT ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, GENERATOR_MODULE);
            ps.setInt(2, TOP_MISSED_CLASSES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sb.append("| `").append(shortenPackage(rs.getString("pkg")))
                      .append(".").append(rs.getString("cls")).append("`\n");
                    sb.append("| ").append(rs.getLong("lm")).append("\n");
                    sb.append("| ").append(pct(rs.getLong("lc"), rs.getLong("lm"))).append("\n");
                }
            }
        }
        sb.append("|===\n\n");
    }

    /**
     * One row per sealed leaf: the classifier-trace count from {@link LeafCoverageReport}'s
     * dimension beside the leaf class's line coverage from this one. The two facts are
     * orthogonal and the four quadrants are four different failure modes; only two are
     * actionable, and neither report can see them alone.
     */
    private static void renderLeafJoinTable(Connection conn, StringBuilder sb,
            List<LeafCoverageReport.Leaf> leaves, boolean tracesPresent) throws SQLException {
        sb.append("== Classification leaves beside class coverage\n\n");
        sb.append("One row per sealed leaf in the hierarchies the inference-axis report ")
          .append("enumerates, joining that leaf's classifier-trace count with its implementing ")
          .append("class's line coverage. Traces with low coverage: the classification is ")
          .append("demonstrated while most of its class never runs. No traces and no coverage: ")
          .append("dead weight. No traces with high coverage: the class is exercised by ")
          .append("something other than the corpus, usually a test asserting the classifier ")
          .append("rather than the classification. Traces with high coverage is the healthy ")
          .append("case. Only the first two are actionable.\n\n");
        if (!tracesPresent) {
            sb.append("_No classifier traces were found in this build (it likely ran with ")
              .append("`-Dleaf-coverage.skip`), so the trace column reads `-` rather than a ")
              .append("real zero._\n\n");
        }

        Map<String, Long> traceCounts = new LinkedHashMap<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT leaf, COUNT(*) AS n FROM trace WHERE op = 'classify' GROUP BY leaf")) {
            while (rs.next()) {
                traceCounts.put(rs.getString("leaf"), rs.getLong("n"));
            }
        }
        Map<String, long[]> lineByCls = new LinkedHashMap<>();
        String sql = """
            SELECT cls, SUM(line_missed) AS lm, SUM(line_covered) AS lc
            FROM combined
            WHERE module = ? AND pkg = ?
            GROUP BY cls
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, GENERATOR_MODULE);
            ps.setString(2, MODEL_PACKAGE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    lineByCls.put(rs.getString("cls"),
                        new long[] {rs.getLong("lm"), rs.getLong("lc")});
                }
            }
        }

        sb.append("[cols=\"2,3,1,1\", options=\"header\"]\n|===\n");
        sb.append("| Hierarchy | Leaf | Traces | Line\n");
        // Iterating the parsed list rather than a staged table keeps the hierarchy-then-name
        // ordering parseLeaves already established.
        for (LeafCoverageReport.Leaf leaf : leaves) {
            long[] lines = lineByCls.get(leaf.fqn());
            sb.append("| ").append(leaf.hierarchy()).append("\n");
            sb.append("| `").append(leaf.fqn()).append("`\n");
            sb.append("| ").append(tracesPresent
                ? Long.toString(traceCounts.getOrDefault(leaf.fqn(), 0L)) : "-").append("\n");
            sb.append("| ").append(lines == null ? "-" : pct(lines[1], lines[0])).append("\n");
        }
        sb.append("|===\n");
    }

    private static String shortenPackage(String pkg) {
        return pkg.replace("no.sikt.graphitron.", "");
    }

    /** Percentage of covered over covered plus missed, one decimal; {@code -} when there is nothing to cover. */
    static String pct(long covered, long missed) {
        long total = covered + missed;
        if (total == 0) return "-";
        return String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * covered / total);
    }
}
