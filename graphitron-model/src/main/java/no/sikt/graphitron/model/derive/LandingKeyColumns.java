package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGMAPPING_LANDING_KEY_COLUMN_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * Writes {@code graphitron_argmapping_landing_key_column}: the key column each written argMapping path
 * projects out of the node id it lands on, from the rule view beside it, marked and swept.
 *
 * <p>A step of {@link OccurrenceBindings}, right after {@link OccurrenceNodeIds} states the node ids,
 * which the rule reads; the landings themselves are the argMapping stage's, written earlier.
 */
public final class LandingKeyColumns {

    private LandingKeyColumns() {}

    /** States the key columns projected by the landings. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var t = GRAPHITRON_ARGMAPPING_LANDING_KEY_COLUMN;
        var r = GRAPHITRON_ARGMAPPING_LANDING_KEY_COLUMN_RULE;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.PARAMETER_NAME,
                t.CALL_COORDINATE, t.LANDING_COORDINATE, t.BOUND, t.POSITION, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER,
                    r.PARAMETER_NAME, r.CALL_COORDINATE, r.LANDING_COORDINATE, r.BOUND, r.POSITION,
                    val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .join(o).on(o.GRAPH_NAME.eq(r.GRAPH_NAME), o.COORDINATE.eq(r.LANDING_COORDINATE))
                .where(r.GRAPH_NAME.eq(graph)))
            .onConflict(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER, t.PARAMETER_NAME,
                t.CALL_COORDINATE, t.BOUND)
            .doUpdate()
            .set(t.LANDING_COORDINATE, excluded(t.LANDING_COORDINATE))
            .set(t.POSITION, excluded(t.POSITION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();
    }

    /** Deletes the key columns this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var t = GRAPHITRON_ARGMAPPING_LANDING_KEY_COLUMN;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
