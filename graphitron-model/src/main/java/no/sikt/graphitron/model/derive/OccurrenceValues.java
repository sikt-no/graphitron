package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING_VALUE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_VALUE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_binding_value}: the member each occurrence with an object
 * source reads its value by, off the accessor path that meets its need, under a {@code VALUE} row of
 * {@code graphitron_occurrence_binding}, marked and swept.
 *
 * <p>Runs after the occurrence depths, whose accessor paths it reads. The sweep deletes the stale
 * {@code VALUE} bindings, and the subtype rows under them go by the cascade.
 */
public final class OccurrenceValues {

    private OccurrenceValues() {}

    private static final String VALUE = "VALUE";

    /** States the graph's value bindings and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var v = GRAPHITRON_OCCURRENCE_BINDING_VALUE;
        var r = GRAPHITRON_OCCURRENCE_VALUE_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline(VALUE), val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(v)
            .columns(v.GRAPH_NAME, v.COORDINATE, v.BOUND, v.KIND, v.SOURCE_CLASS, v.VALUE_CLASS,
                v.SLOT_SOURCE_NAME, v.SLOT_CLASS_NAME, v.SLOT_METHOD_NAME, v.SLOT_DESCRIPTOR,
                v.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline(VALUE), r.SOURCE_CLASS,
                    r.VALUE_CLASS, r.SLOT_SOURCE_NAME, r.SLOT_CLASS_NAME, r.SLOT_METHOD_NAME,
                    r.SLOT_DESCRIPTOR, val(touchedAt, v.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(v.GRAPH_NAME, v.COORDINATE, v.BOUND)
            .doUpdate()
            .set(v.SOURCE_CLASS, excluded(v.SOURCE_CLASS))
            .set(v.VALUE_CLASS, excluded(v.VALUE_CLASS))
            .set(v.SLOT_SOURCE_NAME, excluded(v.SLOT_SOURCE_NAME))
            .set(v.SLOT_CLASS_NAME, excluded(v.SLOT_CLASS_NAME))
            .set(v.SLOT_METHOD_NAME, excluded(v.SLOT_METHOD_NAME))
            .set(v.SLOT_DESCRIPTOR, excluded(v.SLOT_DESCRIPTOR))
            .set(v.TOUCHED_AT, excluded(v.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq(VALUE))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
