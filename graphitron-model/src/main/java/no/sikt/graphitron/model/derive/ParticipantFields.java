package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CROSS_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CROSS_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INTERFACE_SHARED_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_INTERFACE_SHARED_FIELD_RULE;
import static org.jooq.impl.DSL.val;

/**
 * Writes the facts a discriminated read of an interface's participants scopes each participant by:
 * {@code graphitron_field_cross_column}, a scalar field read one step off its type's own table, and
 * {@code graphitron_interface_shared_field}, a field the participants answer under one alias, each
 * from its rule view, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}: the join paths both rules read are occurrence-depths'.
 */
public final class ParticipantFields {

    private ParticipantFields() {}

    /** States the graph's cross columns and shared fields, then deletes those this reading did not mark. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_FIELD_CROSS_COLUMN;
        var cr = GRAPHITRON_FIELD_CROSS_COLUMN_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME, c.TOUCHED_AT)
            .select(dsl.select(cr.GRAPH_NAME, cr.TYPE_NAME, cr.FIELD_NAME, val(touchedAt, c.TOUCHED_AT))
                .from(cr).where(cr.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.TYPE_NAME, c.FIELD_NAME)
            .doUpdate()
            .set(c.TOUCHED_AT, val(touchedAt, c.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(c).where(c.GRAPH_NAME.eq(graph)).and(c.TOUCHED_AT.ne(touchedAt)).execute();

        var s = GRAPHITRON_INTERFACE_SHARED_FIELD;
        var sr = GRAPHITRON_INTERFACE_SHARED_FIELD_RULE;
        dsl.insertInto(s)
            .columns(s.GRAPH_NAME, s.INTERFACE_TYPE, s.FIELD_NAME, s.TOUCHED_AT)
            .select(dsl.select(sr.GRAPH_NAME, sr.INTERFACE_TYPE, sr.FIELD_NAME, val(touchedAt, s.TOUCHED_AT))
                .from(sr).where(sr.GRAPH_NAME.eq(graph)))
            .onConflict(s.GRAPH_NAME, s.INTERFACE_TYPE, s.FIELD_NAME)
            .doUpdate()
            .set(s.TOUCHED_AT, val(touchedAt, s.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(s).where(s.GRAPH_NAME.eq(graph)).and(s.TOUCHED_AT.ne(touchedAt)).execute();
    }
}
