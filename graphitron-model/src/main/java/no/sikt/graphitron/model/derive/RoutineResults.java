package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_RESULT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE_RESULT_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_routine_result}: for each field whose path ends on
 * a {@code @routine}, the function whose result the rows of the type it returns are, filled from its
 * rule view beside it.
 *
 * <p>Reads each field's target and what its path's elements name, so it runs after both and before
 * the readers that ask where a type's rows are read: the result rule, the source tables and the
 * column claims. Marked and swept: upserted on the reading's instant, then the rows this reading did
 * not find are deleted.
 */
public final class RoutineResults {

    private RoutineResults() {}

    /** Re-derives the graph's routine results and sweeps what no longer ends on a routine. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var t = GRAPHITRON_ROUTINE_RESULT;
        var r = GRAPHITRON_ROUTINE_RESULT_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.TYPE_NAME, t.FIELD_NAME, t.TARGET_TYPE_NAME,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME, r.TARGET_TYPE_NAME,
                    r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onDuplicateKeyUpdate()
            .set(t.TARGET_TYPE_NAME, excluded(t.TARGET_TYPE_NAME))
            .set(t.TABLE_SOURCE_NAME, excluded(t.TABLE_SOURCE_NAME))
            .set(t.TABLE_SCHEMA, excluded(t.TABLE_SCHEMA))
            .set(t.TABLE_NAME, excluded(t.TABLE_NAME))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graphName))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
