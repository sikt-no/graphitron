package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_EXPRESSION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_EXPRESSION_BINDING_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_EXPRESSION_CANDIDATE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_EXPRESSION_RULE;
import static org.jooq.impl.DSL.excluded;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;
import static org.jooq.impl.DSL.val;

/**
 * Writes the expressions the {@code @externalField} occurrences compute: an {@code EXPRESSION} row of
 * {@code graphitron_occurrence_binding} at each table the occurrence's values are rows of, the call on
 * it, its candidate, the method it pins and the table fill, then
 * {@code graphitron_occurrence_expression}, which ties the binding to its call. Each from its own rule
 * view, marked with this reading.
 *
 * <p>A step of {@link OccurrenceBindings}, after the source tables {@link OccurrenceDepths} reads are
 * written. The binding is written whether or not a method could be pinned, so a table the method does
 * not take is the stage's defect, judged against a binding with no call. Of the call relations it owns
 * the expression calls' rows and sweeps them; the service calls' are {@link OccurrenceProducers}', the
 * condition calls' {@link OccurrenceConditions}'.
 */
public final class OccurrenceExpressions {

    private OccurrenceExpressions() {}

    /** States the expression bindings, their calls and what fills them. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        var br = GRAPHITRON_OCCURRENCE_EXPRESSION_BINDING_RULE;
        dsl.insertInto(b)
            .columns(b.GRAPH_NAME, b.COORDINATE, b.BOUND, b.KIND, b.TOUCHED_AT)
            .select(dsl
                .select(br.GRAPH_NAME, br.COORDINATE, br.BOUND, inline("EXPRESSION"), val(touchedAt, b.TOUCHED_AT))
                .from(br)
                .join(o).on(o.GRAPH_NAME.eq(br.GRAPH_NAME), o.COORDINATE.eq(br.COORDINATE))
                .where(br.GRAPH_NAME.eq(graph)))
            .onConflict(b.GRAPH_NAME, b.COORDINATE, b.BOUND)
            .doUpdate()
            .set(b.TOUCHED_AT, excluded(b.TOUCHED_AT))
            .execute();

        var k = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
        var kr = GRAPHITRON_OCCURRENCE_EXPRESSION_CANDIDATE_RULE;
        // A candidate this reading no longer states goes before the pin reads them.
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph), k.CALL_KIND.eq("EXPRESSION"))
            .andNotExists(selectOne().from(kr)
                .where(kr.GRAPH_NAME.eq(k.GRAPH_NAME), kr.COORDINATE.eq(k.COORDINATE), kr.CALL.eq(k.CALL),
                    kr.METHOD_SOURCE_NAME.eq(k.METHOD_SOURCE_NAME), kr.METHOD_CLASS_NAME.eq(k.METHOD_CLASS_NAME),
                    kr.METHOD_NAME.eq(k.METHOD_NAME), kr.METHOD_DESCRIPTOR.eq(k.METHOD_DESCRIPTOR)))
            .execute();
        OccurrenceProducers.refresh(dsl, k, kr, graph, null, touchedAt, noCondition());
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_RULE, graph, null,
            touchedAt, GRAPHITRON_OCCURRENCE_CALL_RULE.CALL_KIND.eq("EXPRESSION"));
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE,
            graph, null, touchedAt, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE.CALL_KIND.eq("EXPRESSION"));
        // Every expression call is stated by now, so one this reading did not mark is stale; it goes
        // before the expressions and the fills read the method it pinned last time.
        OccurrenceProducers.sweepCalls(dsl, graph, touchedAt, "EXPRESSION");
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_EXPRESSION, GRAPHITRON_OCCURRENCE_EXPRESSION_RULE,
            graph, null, touchedAt, noCondition());
        OccurrenceCallParameters.writeExpressions(dsl, graph, touchedAt);
    }

    /**
     * Deletes the expression calls' fills, the expressions and the {@code EXPRESSION} bindings this
     * reading did not mark.
     */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        OccurrenceCallParameters.sweep(dsl, graph, touchedAt, "EXPRESSION");
        var e = GRAPHITRON_OCCURRENCE_EXPRESSION;
        dsl.deleteFrom(e)
            .where(e.GRAPH_NAME.eq(graph))
            .and(e.TOUCHED_AT.ne(touchedAt))
            .execute();
        var b = GRAPHITRON_OCCURRENCE_BINDING;
        dsl.deleteFrom(b)
            .where(b.GRAPH_NAME.eq(graph))
            .and(b.KIND.eq("EXPRESSION"))
            .and(b.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
