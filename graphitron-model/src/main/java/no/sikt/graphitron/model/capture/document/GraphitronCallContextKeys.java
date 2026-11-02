package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_call_context_key}: the context key that fills each parameter of
 * each call, inserted from {@code graphitron_call_context_key_rule} for one graph.
 *
 * <p>A step of the graphitron anchor, after the argMapping sites, which the rule reads to find the
 * call a {@code $context} pair belongs to. Marked and swept: the statement upserts on the
 * reading's instant and the delete after it takes the keys this reading no longer finds.
 */
final class GraphitronCallContextKeys {

    private GraphitronCallContextKeys() {}

    static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var r = GRAPHITRON_CALL_CONTEXT_KEY_RULE;
        var t = GRAPHITRON_CALL_CONTEXT_KEY;
        dsl.insertInto(t)
            .columns(t.GRAPH_NAME, t.SOURCE_NAME, t.SOURCE_LINE, t.SOURCE_COLUMN, t.PARAMETER_NAME,
                t.CONTEXT_KEY, t.ORIGIN, t.ORIGIN_LINE, t.ORIGIN_COLUMN, t.ORIGIN_POSITION,
                t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.SOURCE_NAME, r.SOURCE_LINE, r.SOURCE_COLUMN,
                    r.PARAMETER_NAME, r.CONTEXT_KEY, r.ORIGIN, r.ORIGIN_LINE, r.ORIGIN_COLUMN,
                    r.ORIGIN_POSITION, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.CONTEXT_KEY, excluded(t.CONTEXT_KEY))
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.ORIGIN_LINE, excluded(t.ORIGIN_LINE))
            .set(t.ORIGIN_COLUMN, excluded(t.ORIGIN_COLUMN))
            .set(t.ORIGIN_POSITION, excluded(t.ORIGIN_POSITION))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
