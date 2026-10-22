package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_READ;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;

/**
 * How long a capture spends on each statement it runs, recorded in {@code store_graph_write} for the
 * tables it writes and {@code store_graph_read} for the relations it reads.
 *
 * <p>A listener on the writing connection: every insert, merge, update and delete a capture runs is
 * added to the table it names, and every query to the first relation it reads from, under the
 * gatherer {@link Progress} last started, so no stage times itself and a statement outside any stage
 * class is timed all the same. A stage that writes from its rule evaluates the rule inside its write;
 * one that fetches its rule into Java and inserts the rows evaluates it inside the read, which is why
 * reads are timed too, through their fetch. A read under a millisecond in a capture is not recorded:
 * the many small lookups would multiply the rows and say nothing.
 *
 * <p>Counted only between the capture's own start and finish, held in memory meanwhile and written
 * once the capture finishes under the instant it began, so a capture that stops partway writes
 * nothing and every finished capture keeps its own rows. The progress rows and these relations' own
 * are not counted.
 */
public final class WriteTimes implements ExecuteListener {

    private static final long serialVersionUID = 1L;

    private static final Pattern WRITE = Pattern.compile(
        "^\\s*(?:insert\\s+into|merge\\s+into|update|delete\\s+from)\\s+(?:\"?PUBLIC\"?\\s*\\.\\s*)?\"?(\\w+)\"?",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern READ = Pattern.compile(
        "(?s)^\\s*(?:select|with)\\b.*?\\bfrom\\s+(?:\"?PUBLIC\"?\\s*\\.\\s*)?\"?(\\w+)\"?",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> UNCOUNTED = Set.of("STORE_GRAPH_PROGRESS", "STORE_GRAPH_WRITE",
        "STORE_GRAPH_READ");
    private static final long READ_FLOOR_NANOS = 1_000_000;
    private static final String STARTED = WriteTimes.class.getName() + ".started";

    private record Key(String gatherer, String table) {}

    private static final class Total {
        int statements;
        long rows;
        long nanos;
    }

    private final transient Map<Key, Total> totals = new LinkedHashMap<>();
    private final transient Map<Key, Total> reads = new LinkedHashMap<>();
    private String graph;
    private LocalDateTime capturedAt;
    private String gatherer = Progress.CAPTURE;

    /** Whether a statement wrote the relation it names or read from it. */
    public enum Kind { WRITE, READ }

    /**
     * What one capture of a graph spent on one relation: written or read, by which gatherer, in how
     * many statements, over how many rows and how long.
     */
    public record Statement(String graph, Kind kind, String relation, String gatherer, int statements,
                            long rows, long millis) {}

    /**
     * The {@code limit} relations {@code graphName}'s latest finished capture spent longest on,
     * written or read, or, where {@code graphName} is null, the slowest across every graph's latest
     * capture.
     */
    public static List<Statement> slowest(DSLContext dsl, String graphName, int limit) {
        var w = STORE_GRAPH_WRITE;
        var latest = STORE_GRAPH_WRITE.as("latest");
        var statements = new ArrayList<Statement>(dsl
            .select(w.GRAPH_NAME, w.TABLE_NAME, w.GATHERER_NAME, w.STATEMENTS, w.ROWS_WRITTEN,
                w.TIME_SPENT_MS)
            .from(w)
            .where(graphName == null ? noCondition() : w.GRAPH_NAME.eq(graphName))
            .and(w.CAPTURED_AT.eq(dsl.select(max(latest.CAPTURED_AT)).from(latest)
                .where(latest.GRAPH_NAME.eq(w.GRAPH_NAME))))
            .fetch(row -> new Statement(row.value1(), Kind.WRITE, row.value2(), row.value3(),
                row.value4(), row.value5(), row.value6())));
        var r = STORE_GRAPH_READ;
        statements.addAll(dsl
            .select(r.GRAPH_NAME, r.RELATION_NAME, r.GATHERER_NAME, r.STATEMENTS, r.ROWS_READ,
                r.TIME_SPENT_MS)
            .from(r)
            .where(graphName == null ? noCondition() : r.GRAPH_NAME.eq(graphName))
            .and(r.CAPTURED_AT.eq(dsl.select(max(latest.CAPTURED_AT)).from(latest)
                .where(latest.GRAPH_NAME.eq(r.GRAPH_NAME))))
            .fetch(row -> new Statement(row.value1(), Kind.READ, row.value2(), row.value3(),
                row.value4(), row.value5(), row.value6())));
        statements.sort(Comparator.comparingLong(Statement::millis).reversed()
            .thenComparing(Statement::graph).thenComparing(Statement::relation)
            .thenComparing(Statement::kind));
        return statements.subList(0, Math.min(limit, statements.size()));
    }

    /** The listener {@code dsl} writes through, or null where its store was built without one. */
    static WriteTimes of(DSLContext dsl) {
        for (var provider : dsl.configuration().executeListenerProviders()) {
            if (provider.provide() instanceof WriteTimes times) {
                return times;
            }
        }
        return null;
    }

    /**
     * A capture of {@code graphName} begins at {@code startedAt}: what an earlier one counted and did
     * not write goes.
     */
    synchronized void begin(String graphName, LocalDateTime startedAt) {
        totals.clear();
        reads.clear();
        graph = graphName;
        capturedAt = startedAt;
        gatherer = Progress.CAPTURE;
    }

    /** {@code name} is the gatherer now writing; {@link Progress#CAPTURE} between gatherers. */
    synchronized void gatherer(String name) {
        gatherer = name;
    }

    /** The capture finished: its totals are written under the instant it began. */
    void finish(DSLContext dsl) {
        String graphName;
        LocalDateTime at;
        Map<Key, Total> written;
        Map<Key, Total> read;
        synchronized (this) {
            graphName = graph;
            at = capturedAt;
            graph = null;
            written = new LinkedHashMap<>(totals);
            read = new LinkedHashMap<>(reads);
            totals.clear();
            reads.clear();
        }
        if (graphName == null) {
            return;
        }
        if (!written.isEmpty()) {
            var t = STORE_GRAPH_WRITE;
            var insert = dsl.insertInto(t, t.GRAPH_NAME, t.CAPTURED_AT, t.GATHERER_NAME, t.TABLE_NAME,
                t.STATEMENTS, t.ROWS_WRITTEN, t.TIME_SPENT_MS);
            written.forEach((key, total) -> insert.values(graphName, at, key.gatherer(), key.table(),
                total.statements, total.rows, total.nanos / 1_000_000));
            insert.execute();
        }
        read.values().removeIf(total -> total.nanos < READ_FLOOR_NANOS);
        if (!read.isEmpty()) {
            var t = STORE_GRAPH_READ;
            var insert = dsl.insertInto(t, t.GRAPH_NAME, t.CAPTURED_AT, t.GATHERER_NAME,
                t.RELATION_NAME, t.STATEMENTS, t.ROWS_READ, t.TIME_SPENT_MS);
            read.forEach((key, total) -> insert.values(graphName, at, key.gatherer(), key.table(),
                total.statements, total.rows, total.nanos / 1_000_000));
            insert.execute();
        }
    }

    @Override
    public synchronized void executeStart(ExecuteContext ctx) {
        if (graph != null) {
            ctx.data(STARTED, System.nanoTime());
        }
    }

    /** After any fetch, so a read's time is the time its rows took to produce as well. */
    @Override
    public void end(ExecuteContext ctx) {
        if (!(ctx.data(STARTED) instanceof Long started)) {
            return;
        }
        long nanos = System.nanoTime() - started;
        String[] batch = ctx.batchSQL();
        String sql = ctx.sql() != null ? ctx.sql() : batch.length > 0 ? batch[0] : null;
        if (sql == null) {
            return;
        }
        var write = WRITE.matcher(sql);
        boolean writes = write.find();
        var read = writes ? null : READ.matcher(sql);
        if (!writes && !read.find()) {
            return;
        }
        String relation = (writes ? write.group(1) : read.group(1)).toUpperCase();
        if (UNCOUNTED.contains(relation)) {
            return;
        }
        long rows = 0;
        if (!writes) {
            rows = ctx.result() == null ? 0 : ctx.result().size();
        } else if (ctx.batchRows().length > 0) {
            for (int n : ctx.batchRows()) {
                rows += Math.max(n, 0);
            }
        } else {
            rows = Math.max(ctx.rows(), 0);
        }
        synchronized (this) {
            if (graph == null) {
                return;
            }
            var total = (writes ? totals : reads).computeIfAbsent(new Key(gatherer, relation),
                k -> new Total());
            total.statements++;
            total.rows += rows;
            total.nanos += nanos;
        }
    }
}
