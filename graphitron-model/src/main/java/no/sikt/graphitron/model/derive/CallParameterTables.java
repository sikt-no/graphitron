package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_PARAMETER_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_PARAMETER_TABLE_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_call_parameter_table}: which catalog tables each parameter of a method the
 * graph's code references name can be handed, from the rule view beside it, marked and swept.
 *
 * <p>Runs once the code references have resolved, and before the stages choosing a call's candidates
 * and classifying its parameters, which read it.
 */
public final class CallParameterTables {

    private CallParameterTables() {}

    /** States the graph's call parameter fits, and sweeps what the reading no longer finds. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_CALL_PARAMETER_TABLE;
        var r = GRAPHITRON_CALL_PARAMETER_TABLE_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR, t.POSITION,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME, t.FIT, t.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.SOURCE_NAME, r.CLASS_NAME, r.METHOD_NAME, r.DESCRIPTOR, r.POSITION,
                    r.TABLE_SOURCE_NAME, r.TABLE_SCHEMA, r.TABLE_NAME, r.FIT, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.SOURCE_NAME, t.CLASS_NAME, t.METHOD_NAME, t.DESCRIPTOR, t.POSITION,
                t.TABLE_SOURCE_NAME, t.TABLE_SCHEMA, t.TABLE_NAME)
            .doUpdate()
            .set(t.FIT, excluded(t.FIT))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
