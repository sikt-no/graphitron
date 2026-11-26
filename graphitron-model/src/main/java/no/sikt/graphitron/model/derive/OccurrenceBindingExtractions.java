package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_binding_extraction}: how the value each argument or input field
 * supplies reaches the call parameter it binds, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBuilds} after the node ids' slots and shapes, the last of its inputs;
 * the parameter bindings and built classes it reads are written by the stages before.
 */
public final class OccurrenceBindingExtractions {

    private OccurrenceBindingExtractions() {}

    /** States how each binding's value reaches its parameter. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        var r = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION_RULE;
        dsl.insertInto(e)
            .columns(e.GRAPH_NAME, e.COORDINATE, e.BOUND, e.EXTRACTION, e.COMPONENT, e.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.EXTRACTION, r.COMPONENT, val(touchedAt, e.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(e.GRAPH_NAME, e.COORDINATE, e.BOUND)
            .doUpdate()
            .set(e.EXTRACTION, excluded(e.EXTRACTION))
            .set(e.COMPONENT, excluded(e.COMPONENT))
            .set(e.TOUCHED_AT, excluded(e.TOUCHED_AT))
            .execute();
    }

    /** Deletes the extractions this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var e = GRAPHITRON_OCCURRENCE_BINDING_EXTRACTION;
        dsl.deleteFrom(e)
            .where(e.GRAPH_NAME.eq(graph))
            .and(e.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
