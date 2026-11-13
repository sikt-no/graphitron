package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NESTING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_NESTING_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.selectDistinct;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_nesting}: each occurrence that is a nesting field, its fields
 * read off its parent's row, from {@code graphitron_occurrence_nesting_rule}, marked and swept.
 */
public final class OccurrenceNestings {

    private OccurrenceNestings() {}

    /** Upserts the graph's nesting occurrences, marked with this reading's instant. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var n = GRAPHITRON_OCCURRENCE_NESTING;
        var r = GRAPHITRON_OCCURRENCE_NESTING_RULE;
        dsl.insertInto(n)
            .columns(n.GRAPH_NAME, n.COORDINATE, n.TARGET_TYPE, n.TOUCHED_AT)
            .select(selectDistinct(r.GRAPH_NAME, r.COORDINATE, r.TARGET_TYPE, val(touchedAt, n.TOUCHED_AT))
                .from(r).where(r.GRAPH_NAME.eq(graph)))
            .onConflict(n.GRAPH_NAME, n.COORDINATE)
            .doUpdate()
            .set(n.TARGET_TYPE, excluded(n.TARGET_TYPE))
            .set(n.TOUCHED_AT, excluded(n.TOUCHED_AT))
            .execute();
    }

    /** Deletes the nesting occurrences this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var n = GRAPHITRON_OCCURRENCE_NESTING;
        dsl.deleteFrom(n).where(n.GRAPH_NAME.eq(graph)).and(n.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
