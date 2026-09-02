package no.sikt.graphitron.rewrite.diagnostics;

import no.sikt.graphitron.rewrite.capture.GraphIdentity;
import org.jooq.DSLContext;
import org.slf4j.Logger;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;

/**
 * The graph-ownership preamble every diagnostics-stratum loader shares, on the exact terms
 * {@link no.sikt.graphitron.rewrite.compile.CompileFacts} established for the {@code javac_}
 * family: mint the minimal {@code store_graph} anchor where no capture ever reached this store
 * under the graph (the in-memory session fallback, or a session that has not generated yet),
 * and refuse to touch a partition another checkout's directory owns, warning once per loader.
 */
final class OwnedGraphPartition {

    private OwnedGraphPartition() {}

    /**
     * Prepares the graph's anchor row inside the caller's transaction. Returns {@code false},
     * after warning through {@code log} on the first refusal, when the graph is recorded
     * against a different base directory and the partition is not this session's to touch.
     *
     * @param warned one-element state cell carrying the caller's warn-once flag
     */
    static boolean prepare(DSLContext tx, GraphIdentity graph, Logger log, boolean[] warned) {
        String recorded = tx.select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null) {
            tx.insertInto(STORE_GRAPH)
                .set(STORE_GRAPH.GRAPH_NAME, graph.name())
                .set(STORE_GRAPH.BASE_DIR, graph.baseDir().toString())
                .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
                .onDuplicateKeyIgnore()
                .execute();
            return true;
        }
        if (!recorded.equals(graph.baseDir().toString())) {
            if (!warned[0]) {
                warned[0] = true;
                log.warn("graph '{}' is recorded in the shared fact store for {}, but this dev "
                        + "session's base directory is {}. Leaving that partition's diagnostics "
                        + "alone; set <graphName> so the two modules stop claiming one name.",
                    graph.name(), recorded, graph.baseDir());
            }
            return false;
        }
        return true;
    }
}
