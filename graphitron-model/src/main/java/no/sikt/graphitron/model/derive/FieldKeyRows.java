package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_KEY_ROW_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The capture-cadence writer of {@code graphitron_field_key_row}: for each
 * field that fetches its own rows under a parent holding an object, how it takes its keys off that
 * object, filled from the rule view beside it. Which member an accessor key reads is not stored:
 * it is the member the field's bound name finds on the parent's class, read where it is asked.
 *
 * <p>Reads the producers and results {@link Fetchers} writes and what the path elements name, so it
 * runs after both and before {@link FieldPaths}. Marked and swept: upserted on the reading's
 * instant, then the rows this reading did not find are deleted.
 */
public final class FieldKeyRows {

    private FieldKeyRows() {}

    /** Re-derives the graph's key rows and sweeps what no longer takes keys off its parent. */
    public static void derive(DSLContext dsl, String graphName, LocalDateTime touchedAt) {
        var k = GRAPHITRON_FIELD_KEY_ROW;
        var r = GRAPHITRON_FIELD_KEY_ROW_RULE;
        dsl.insertInto(k)
            .columns(k.GRAPH_NAME, k.TYPE_NAME, k.FIELD_NAME, k.KIND, k.TABLE_SOURCE_NAME,
                k.TABLE_SCHEMA, k.TABLE_NAME, k.ARITY, k.TOUCHED_AT)
            .select(dsl
                .select(r.GRAPH_NAME, r.TYPE_NAME, r.FIELD_NAME, r.KIND, r.TABLE_SOURCE_NAME,
                    r.TABLE_SCHEMA, r.TABLE_NAME, r.ARITY, val(touchedAt, k.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graphName)))
            .onConflict(k.GRAPH_NAME, k.TYPE_NAME, k.FIELD_NAME)
            .doUpdate()
            .set(k.KIND, excluded(k.KIND))
            .set(k.TABLE_SOURCE_NAME, excluded(k.TABLE_SOURCE_NAME))
            .set(k.TABLE_SCHEMA, excluded(k.TABLE_SCHEMA))
            .set(k.TABLE_NAME, excluded(k.TABLE_NAME))
            .set(k.ARITY, excluded(k.ARITY))
            .set(k.TOUCHED_AT, excluded(k.TOUCHED_AT))
            .execute();
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graphName))
            .and(k.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
