package no.sikt.graphitron.model.sink;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_PROGRESS;

/**
 * One gatherer's run of a capture, recorded in {@code store_graph_progress} so the store says how
 * far a reading got.
 *
 * <p>A capture commits as it goes: each gatherer commits its own work in whatever transactions
 * suit it, so a reading that stops partway leaves the gatherers it finished committed and the rest
 * as the previous reading left them. Mark and sweep is what makes that safe: a row still true is
 * never deleted, only one the reading no longer finds. This records the run and nothing else; it
 * opens no transaction and asks for none.
 *
 * <p>A gatherer calls {@link #started} before it touches anything, so one that never returns has
 * already named itself, and {@link #completed} once its work is committed. One that throws never
 * reaches the second call, which is what leaves its row unfinished. The instants are the wall
 * clock's, the reading's own staying on {@code store_graph.last_captured} and on every row it
 * marks.
 *
 * <p>A run for no graph records nothing: the classpath families are store-global, and a reading
 * claimed for no graph is no graph's capture to report on.
 */
public final class Progress {

    /** The row spanning the whole capture, which the capture records itself. */
    public static final String CAPTURE = "capture";

    private Progress() {
    }

    /** Records that {@code gatherer} began reading {@code graphName}. */
    public static void started(DSLContext dsl, String graphName, String gatherer) {
        if (graphName == null) {
            return;
        }
        var t = STORE_GRAPH_PROGRESS;
        var now = LocalDateTime.now();
        dsl.insertInto(t, t.GRAPH_NAME, t.GATHERER_NAME, t.LAST_STARTED)
            .values(graphName, gatherer, now)
            .onDuplicateKeyUpdate()
            .set(t.LAST_STARTED, now)
            .execute();
    }

    /** Records that {@code gatherer} finished reading {@code graphName}, its work committed. */
    public static void completed(DSLContext dsl, String graphName, String gatherer) {
        if (graphName == null) {
            return;
        }
        var t = STORE_GRAPH_PROGRESS;
        dsl.update(t)
            .set(t.LAST_COMPLETED, LocalDateTime.now())
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.GATHERER_NAME.eq(gatherer))
            .execute();
    }
}
