package no.sikt.graphitron.model.capture.document;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CALL_CONTEXT_KEY_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.val;

/**
 * The writer of {@code graphitron_call_context_key}: every context key written for a parameter of
 * a call, each marked whether it is the parameter's only one, inserted from
 * {@code graphitron_call_context_key_rule} for one graph.
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
            .columns(t.GRAPH_NAME, t.COORDINATE, t.DIRECTIVE_NAME, t.ORDINAL, t.OWNER,
                t.PARAMETER_NAME, t.CONTEXT_KEY, t.ORIGIN, t.SOURCE_NAME, t.ORIGIN_LINE,
                t.ORIGIN_COLUMN, t.ORIGIN_POSITION, t.WRITTEN_ONCE, t.TOUCHED_AT)
            .select(dsl.select(r.GRAPH_NAME, r.COORDINATE, r.DIRECTIVE_NAME, r.ORDINAL, r.OWNER,
                    r.PARAMETER_NAME, r.CONTEXT_KEY, r.ORIGIN, r.SOURCE_NAME, r.ORIGIN_LINE,
                    r.ORIGIN_COLUMN, r.ORIGIN_POSITION, r.WRITTEN_ONCE, val(touchedAt, t.TOUCHED_AT))
                .from(r)
                .where(r.GRAPH_NAME.eq(graph)))
            .onDuplicateKeyUpdate()
            .set(t.CONTEXT_KEY, excluded(t.CONTEXT_KEY))
            .set(t.ORIGIN, excluded(t.ORIGIN))
            .set(t.SOURCE_NAME, excluded(t.SOURCE_NAME))
            .set(t.WRITTEN_ONCE, excluded(t.WRITTEN_ONCE))
            .set(t.TOUCHED_AT, excluded(t.TOUCHED_AT))
            .execute();

        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
