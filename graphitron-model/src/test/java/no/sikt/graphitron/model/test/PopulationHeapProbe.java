package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.boot.StoreUse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import javax.management.JMException;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the fact population keeps on the heap: warms an evenly spread slice of its documents,
 * collects, and writes the live heap with a class histogram grouped by the library that owns each
 * class to {@code target/heap-probe/}. A measuring tool rather than a check, inert unless
 * {@value #DOCUMENTS} is set; a run takes a few minutes:
 *
 * <pre>{@code
 * mvn test -pl graphitron-model -Dtest=PopulationHeapProbe -Dheap.probe.documents=120 \
 *     -Dheap.probe.label=d120
 * }</pre>
 *
 * <p>{@code graphitron.populationCacheSize} and {@code graphitron.populationShards} vary the
 * population's statement cache and its stores a catalog. At {@code 0} documents it holds one booted
 * store with no graph instead, what a store costs before its first capture, and {@value #VIEWS}
 * drops {@code all} or {@code half} of that store's view text, or with {@code tokens} empties the
 * token list each view's parsed query keeps, before it is measured.
 */
@EnabledIfSystemProperty(named = PopulationHeapProbe.DOCUMENTS, matches = "\\d+")
class PopulationHeapProbe {

    static final String DOCUMENTS = "heap.probe.documents";
    static final String LABEL = "heap.probe.label";
    static final String VIEWS = "heap.probe.views";

    private static final int TOP_CLASSES = 40;

    @Test
    void reportsWhatThePopulationKeeps() throws Exception {
        int wanted = Integer.getInteger(DOCUMENTS);
        var population = FactGraphs.population();
        try {
            var all = new ArrayList<>(population.paths());
            var chosen = spread(all, wanted);
            long before = liveHeap();
            long started = System.nanoTime();
            var bare = wanted == 0
                ? FactStores.fileBacked(Files.createTempDirectory("heap-probe"), StoreUse.POPULATION) : null;
            var dropped = bare == null ? "" : dropViews(bare.dsl(), System.getProperty(VIEWS, "none"));
            var views = bare == null ? -1
                : bare.dsl().fetchValue("SELECT COUNT(*) FROM INFORMATION_SCHEMA.VIEWS", Long.class);
            population.warm(chosen);
            long warmMillis = (System.nanoTime() - started) / 1_000_000;
            long after = liveHeap();
            var histogram = histogram();

            var report = new StringBuilder();
            report.append("documents ").append(chosen.size()).append(" of ").append(all.size()).append('\n');
            report.append("stores ").append(bare == null ? FactGraphs.boots() : 1).append('\n');
            report.append("views ").append(views).append(dropped).append('\n');
            report.append("populationCacheSize ")
                .append(System.getProperty("graphitron.populationCacheSize", "default")).append('\n');
            report.append("warm_ms ").append(warmMillis).append('\n');
            report.append("live_mb_before ").append(before >> 20).append('\n');
            report.append("live_mb_after ").append(after >> 20).append('\n');
            report.append("\nby owner, MB\n");
            byOwner(histogram).forEach((owner, bytes) ->
                report.append(String.format("%-16s %8d%n", owner, bytes >> 20)));
            report.append("\ntop classes: instances, MB, class\n");
            histogram.stream().limit(TOP_CLASSES).forEach(row ->
                report.append(String.format("%10d %8d  %s%n", row.instances(), row.bytes() >> 20, row.type())));

            var out = Path.of("target", "heap-probe");
            Files.createDirectories(out);
            Files.writeString(out.resolve(System.getProperty(LABEL, "documents-" + wanted) + ".txt"),
                report.toString());
            System.out.println("HEAP-PROBE\n" + report);
            if (bare != null) {
                bare.close();
            }
        } finally {
            population.close();
        }
    }

    /**
     * Drops {@code share} of the store's views, {@code all} or {@code half} of their text, the longest
     * first, a dependent view going with what it reads; reports the text before and after.
     */
    private static String dropViews(org.jooq.DSLContext dsl, String share) {
        if (share.equals("none")) {
            return "";
        }
        if (share.equals("tokens")) {
            return dropViewTokens(dsl);
        }
        var views = dsl.fetch("SELECT TABLE_NAME, LENGTH(VIEW_DEFINITION) FROM INFORMATION_SCHEMA.VIEWS"
            + " WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY 2 DESC");
        long total = views.stream().mapToLong(row -> row.get(1, Long.class)).sum();
        long target = share.equals("all") ? total : total / 2;
        long gone = 0;
        for (var row : views) {
            if (gone >= target) {
                break;
            }
            gone += row.get(1, Long.class);
            dsl.execute("DROP VIEW IF EXISTS \"" + row.get(0, String.class) + "\" CASCADE");
        }
        long left = ((Number) dsl.fetchValue("SELECT COALESCE(SUM(LENGTH(VIEW_DEFINITION)), 0)"
            + " FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = 'PUBLIC'")).longValue();
        return " (dropped " + share + ": view text " + total + " chars before, " + left + " after)";
    }

    /**
     * Empties the token list each view's parsed query keeps, which is what an H2 that dropped it
     * after the parse would save, the views themselves left in place.
     */
    private static String dropViewTokens(org.jooq.DSLContext dsl) {
        int[] views = {0};
        dsl.connection(connection -> {
            var session = (org.h2.engine.SessionLocal) connection.unwrap(org.h2.jdbc.JdbcConnection.class).getSession();
            var viewQuery = org.h2.table.QueryExpressionTable.class.getDeclaredField("viewQuery");
            var tokens = org.h2.command.Prepared.class.getDeclaredField("sqlTokens");
            viewQuery.setAccessible(true);
            tokens.setAccessible(true);
            for (var schema : session.getDatabase().getAllSchemasNoMeta()) {
                for (var table : schema.getAllTablesAndViews(session)) {
                    if (table instanceof org.h2.table.QueryExpressionTable view && viewQuery.get(view) != null) {
                        tokens.set(viewQuery.get(view), null);
                        views[0]++;
                    }
                }
            }
        });
        return " (token lists emptied on " + views[0] + " views)";
    }

    /** {@code wanted} paths taken at an even stride, so every folder is represented. */
    private static List<String> spread(List<String> paths, int wanted) {
        if (wanted >= paths.size()) {
            return paths;
        }
        var chosen = new ArrayList<String>();
        for (int i = 0; i < wanted; i++) {
            chosen.add(paths.get((int) ((long) i * paths.size() / wanted)));
        }
        return chosen;
    }

    /** The heap still reachable after collections. */
    private static long liveHeap() {
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private record Row(long instances, long bytes, String type) {}

    /**
     * The live class histogram, largest first, as {@code GC.class_histogram} reports it, asked of
     * this JVM's own diagnostic command bean: a {@code jcmd} child attaching to its parent hangs.
     */
    private static List<Row> histogram() throws JMException {
        var histogram = (String) ManagementFactory.getPlatformMBeanServer().invoke(
            new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
            new Object[] {new String[0]}, new String[] {String[].class.getName()});
        var lines = histogram.lines().toList();
        var rows = new ArrayList<Row>();
        for (var line : lines) {
            var cells = line.trim().split("\\s+");
            if (cells.length >= 4 && cells[0].endsWith(":")) {
                rows.add(new Row(Long.parseLong(cells[1]), Long.parseLong(cells[2]), cells[3]));
            }
        }
        return rows;
    }

    private static Map<String, Long> byOwner(List<Row> rows) {
        var owners = new TreeMap<String, Long>();
        rows.forEach(row -> owners.merge(owner(row.type()), row.bytes(), Long::sum));
        return owners;
    }

    /** The library a class belongs to; H2's parsed statements and plans apart from its storage. */
    private static String owner(String type) {
        if (type.startsWith("org.h2.command") || type.startsWith("org.h2.expression")
            || type.startsWith("org.h2.table.TableFilter") || type.startsWith("org.h2.index.IndexCondition")) {
            return "h2-statements";
        }
        if (type.startsWith("org.h2.mvstore")) {
            return "h2-mvstore";
        }
        if (type.startsWith("org.h2.")) {
            return "h2-other";
        }
        if (type.startsWith("no.sikt.")) {
            return "graphitron";
        }
        if (type.startsWith("org.jooq.")) {
            return "jooq";
        }
        if (type.startsWith("graphql.")) {
            return "graphql-java";
        }
        return "jdk-and-arrays";
    }
}
