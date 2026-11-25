package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_column_conversion}: how the value a caller passes becomes the
 * value of the column its argument or input field binds, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings} after {@link OccurrenceColumns}, whose bindings it reads; a
 * binding the columns' sweep deletes takes its conversion with it by the cascade.
 */
public final class OccurrenceColumnConversions {

    private OccurrenceColumnConversions() {}

    /** States the conversion of each column an argument or input field binds. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION;
        var r = GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION_RULE;
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.BOUND, c.CONVERSION, c.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, r.CONVERSION, val(touchedAt, c.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.BOUND)
            .doUpdate()
            .set(c.CONVERSION, excluded(c.CONVERSION))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
    }

    /** Deletes the conversions this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_COLUMN_CONVERSION;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
