package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_FILTER_TERM;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_FILTER_TERM_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ROW_INPUT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_ROW_INPUT_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_row_input}, the arguments and input fields read under a field's
 * rows that its own call does not take, and its subtype {@code graphitron_occurrence_filter_term}, the
 * ones the field's filter compares, each from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, after {@link OccurrenceTables}, whose table bindings say
 * which tables a field's rows are on, and after the column and node id bindings the terms are read
 * off; the producer's bindings, built classes and node fetchers it reads are the occurrence-producers
 * stage's.
 */
public final class OccurrenceFilterTerms {

    private OccurrenceFilterTerms() {}

    /** States the row inputs of the graph's fields, then the filter terms among them. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var i = GRAPHITRON_OCCURRENCE_ROW_INPUT;
        var ir = GRAPHITRON_OCCURRENCE_ROW_INPUT_RULE;
        dsl.insertInto(i)
            .columns(i.GRAPH_NAME, i.COORDINATE, i.FIELD_COORDINATE, i.TABLE_SOURCE_NAME, i.TABLE_SCHEMA,
                i.TABLE_NAME, i.TOUCHED_AT)
            .select(dsl
                .select(ir.GRAPH_NAME, ir.COORDINATE, ir.FIELD_COORDINATE, ir.TABLE_SOURCE_NAME, ir.TABLE_SCHEMA,
                    ir.TABLE_NAME, val(touchedAt, i.TOUCHED_AT))
                .from(ir)
                .where(ir.GRAPH_NAME.eq(graph)))
            .onConflict(i.GRAPH_NAME, i.COORDINATE, i.TABLE_SOURCE_NAME, i.TABLE_SCHEMA, i.TABLE_NAME)
            .doUpdate()
            .set(i.FIELD_COORDINATE, excluded(i.FIELD_COORDINATE))
            .set(i.TOUCHED_AT, excluded(i.TOUCHED_AT))
            .execute();
        var t = GRAPHITRON_OCCURRENCE_FILTER_TERM;
        var tr = GRAPHITRON_OCCURRENCE_FILTER_TERM_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(tr.GRAPH_NAME, tr.COORDINATE, tr.TABLE_SOURCE_NAME, tr.TABLE_SCHEMA, tr.TABLE_NAME,
                    val(touchedAt, t.TOUCHED_AT))
                .from(tr)
                .where(tr.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** Deletes the filter terms, then the row inputs, this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OCCURRENCE_FILTER_TERM;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
        var i = GRAPHITRON_OCCURRENCE_ROW_INPUT;
        dsl.deleteFrom(i)
            .where(i.GRAPH_NAME.eq(graph))
            .and(i.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
