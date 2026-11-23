package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PROJECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PROJECTION_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_projection}, each field occurrence read in a table-bound type's
 * projection, from the rule view beside it, a depth at a time, each depth marked and swept before the
 * next reads it.
 *
 * <p>A step of {@link OccurrenceBindings}, run once {@link OccurrenceNestings} has written and swept,
 * since a field below depth 0 is in a projection through the nesting field above it.
 */
public final class OccurrenceProjections {

    private OccurrenceProjections() {}

    /** States the graph's projected occurrences, depth by depth, then sweeps what no depth marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var p = GRAPHITRON_OCCURRENCE_PROJECTION;
        var r = GRAPHITRON_OCCURRENCE_PROJECTION_RULE;
        // A depth with nothing in a projection has nothing below it in one either.
        for (int depth = 0; ; depth++) {
            int written = dsl.insertInto(p)
                .columns(p.GRAPH_NAME, p.COORDINATE, p.TYPE_NAME, p.TOUCHED_AT)
                .select(selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.TYPE_NAME, val(touchedAt, p.TOUCHED_AT))
                    .from(r)
                    .where(r.GRAPH_NAME.eq(graph))
                    .and(OccurrenceProducers.atDepth(r.GRAPH_NAME, r.COORDINATE, depth)))
                .onConflict(p.GRAPH_NAME, p.COORDINATE)
                .doUpdate()
                .set(p.TYPE_NAME, excluded(p.TYPE_NAME))
                .set(p.TOUCHED_AT, excluded(p.TOUCHED_AT))
                .execute();
            // The depth is complete, so its rows this reading did not mark go now: the next depth
            // reads only what this one stated.
            dsl.deleteFrom(p)
                .where(p.GRAPH_NAME.eq(graph))
                .and(p.TOUCHED_AT.ne(touchedAt))
                .and(OccurrenceProducers.atDepth(p.GRAPH_NAME, p.COORDINATE, depth))
                .execute();
            if (written == 0) {
                break;
            }
        }
        // Rows of a depth below the last one reached.
        dsl.deleteFrom(p).where(p.GRAPH_NAME.eq(graph)).and(p.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
