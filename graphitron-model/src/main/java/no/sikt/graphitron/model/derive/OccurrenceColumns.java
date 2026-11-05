package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_COLUMN_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_occurrence_column}: the column each scalar occurrence reads or binds where
 * its path lands, from the rule view beside it, under a {@code COLUMN} row of
 * {@code graphitron_occurrence_binding}, marked and swept.
 *
 * <p>Runs after the path needs, whose landings it reads, the binding claims, whose written names it
 * reads, and the node ids, whose occurrences read no column. The sweep deletes the stale {@code COLUMN} bindings, and the subtype rows
 * under them go by the cascade.
 */
public final class OccurrenceColumns {

    private OccurrenceColumns() {}

    /** States the graph's column bindings and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var c = GRAPHITRON_OCCURRENCE_COLUMN;
        var r = GRAPHITRON_OCCURRENCE_COLUMN_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("COLUMN"), val(touchedAt, b.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();
        dsl.insertInto(c)
            .columns(c.GRAPH_NAME, c.COORDINATE, c.BOUND, c.KIND, c.TABLE_SOURCE_NAME,
                c.TABLE_SCHEMA, c.TABLE_NAME, c.COLUMN_NAME, c.MATCHED_BY, c.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.COORDINATE, r.BOUND, inline("COLUMN"), r.TABLE_SOURCE_NAME,
                    r.TABLE_SCHEMA, r.TABLE_NAME, r.COLUMN_NAME, r.MATCHED_BY,
                    val(touchedAt, c.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(c.GRAPH_NAME, c.COORDINATE, c.BOUND)
            .doUpdate()
            .set(c.TABLE_SOURCE_NAME, excluded(c.TABLE_SOURCE_NAME))
            .set(c.TABLE_SCHEMA, excluded(c.TABLE_SCHEMA))
            .set(c.TABLE_NAME, excluded(c.TABLE_NAME))
            .set(c.COLUMN_NAME, excluded(c.COLUMN_NAME))
            .set(c.MATCHED_BY, excluded(c.MATCHED_BY))
            .set(c.TOUCHED_AT, excluded(c.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("COLUMN"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
