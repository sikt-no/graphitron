package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_WRITE;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;

/**
 * How long a capture spends writing each table, recorded in {@code store_graph_write}.
 *
 * <p>A listener on the writing connection: every insert, merge, update and delete a capture runs is
 * added to the table it names and the gatherer {@link Progress} last started, so no stage times
 * itself and a write outside any stage class is timed all the same. A stage's rule is evaluated
 * inside its write, which makes the table's total the stage's cost.
 *
 * <p>Counted only between the capture's own start and finish, held in memory meanwhile and written
 * once the capture finishes under the instant it began, so a capture that stops partway writes
 * nothing and every finished capture keeps its own rows. The progress rows and this relation's own
 * are not counted.
 */
public final class WriteTimes implements ExecuteListener {

    private static final long serialVersionUID = 1L;

    private static final Pattern WRITE = Pattern.compile(
        "^\\s*(?:insert\\s+into|merge\\s+into|update|delete\\s+from)\\s+(?:\"?PUBLIC\"?\\s*\\.\\s*)?\"?(\\w+)\"?",
        Pattern.CASE_INSENSITIVE);
    private static final Set<String> UNCOUNTED = Set.of("STORE_GRAPH_PROGRESS", "STORE_GRAPH_WRITE");
    private static final String STARTED = WriteTimes.class.getName() + ".started";

    private record Key(String gatherer, String table) {}

    private static final class Total {
        int statements;
        long rows;
        long nanos;
    }

    private final transient Map<Key, Total> totals = new LinkedHashMap<>();
    private String graph;
    private LocalDateTime capturedAt;
    private String gatherer = Progress.CAPTURE;

    /** One table a graph's capture wrote: which, by which gatherer, in how many statements and how long. */
    public record Write(String graph, String table, String gatherer, int statements, long rows,
                        long millis) {}

    /**
     * The {@code limit} tables {@code graphName}'s latest finished capture took longest to write, or,
     * where {@code graphName} is null, the slowest across every graph's latest capture.
     */
    public static List<Write> slowest(DSLContext dsl, String graphName, int limit) {
        var t = STORE_GRAPH_WRITE;
        var earlier = STORE_GRAPH_WRITE.as("earlier");
        var latest = dsl.select(max(earlier.CAPTURED_AT)).from(earlier)
            .where(earlier.GRAPH_NAME.eq(t.GRAPH_NAME));
        return dsl.select(t.GRAPH_NAME, t.TABLE_NAME, t.GATHERER_NAME, t.STATEMENTS, t.ROWS_WRITTEN,
                t.TIME_SPENT_MS)
            .from(t)
            .where(graphName == null ? noCondition() : t.GRAPH_NAME.eq(graphName))
            .and(t.CAPTURED_AT.eq(latest))
            .orderBy(t.TIME_SPENT_MS.desc(), t.GRAPH_NAME, t.TABLE_NAME, t.GATHERER_NAME)
            .limit(limit)
            .fetch(r -> new Write(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(),
                r.value6()));
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
        Map<Key, Total> counted;
        synchronized (this) {
            graphName = graph;
            at = capturedAt;
            graph = null;
            counted = new LinkedHashMap<>(totals);
            totals.clear();
        }
        if (graphName == null || counted.isEmpty()) {
            return;
        }
        var t = STORE_GRAPH_WRITE;
        var insert = dsl.insertInto(t, t.GRAPH_NAME, t.CAPTURED_AT, t.GATHERER_NAME, t.TABLE_NAME,
            t.STATEMENTS, t.ROWS_WRITTEN, t.TIME_SPENT_MS);
        counted.forEach((key, total) -> insert.values(graphName, at, key.gatherer(), key.table(),
            total.statements, total.rows, total.nanos / 1_000_000));
        insert.execute();
    }

    @Override
    public synchronized void executeStart(ExecuteContext ctx) {
        if (graph != null) {
            ctx.data(STARTED, System.nanoTime());
        }
    }

    @Override
    public void executeEnd(ExecuteContext ctx) {
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
        if (!write.find()) {
            return;
        }
        String table = write.group(1).toUpperCase();
        if (UNCOUNTED.contains(table)) {
            return;
        }
        long rows = 0;
        if (ctx.batchRows().length > 0) {
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
            var total = totals.computeIfAbsent(new Key(gatherer, table), k -> new Total());
            total.statements++;
            total.rows += rows;
            total.nanos += nanos;
        }
    }
}
