package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CALL_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_CANDIDATE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_CONDITION_RULE;
import static org.jooq.impl.DSL.noCondition;
import static org.jooq.impl.DSL.selectOne;

/**
 * Writes the {@code @condition} calls the occurrences make, one per table each binds: the candidate
 * overloads into {@code graphitron_occurrence_call_candidate}, the call and the method it pins,
 * {@code graphitron_occurrence_condition} and the call's fills, each from the condition's own rule
 * view, marked with this reading.
 *
 * <p>The last step of {@link OccurrenceBindings}, after {@link OccurrenceTables}: which tables a
 * condition is applied to is the bindings, so its call waits on them. Of the call relations it owns
 * the condition calls' rows and sweeps them; the service calls' are {@link OccurrenceProducers}'.
 */
public final class OccurrenceConditions {

    private OccurrenceConditions() {}

    /** States the {@code @condition} calls and what fills them. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var k = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
        var kr = GRAPHITRON_OCCURRENCE_CONDITION_CANDIDATE_RULE;
        // A candidate this reading no longer states goes before the pin reads them.
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph), k.CALL_KIND.eq("CONDITION"))
            .andNotExists(selectOne().from(kr)
                .where(kr.GRAPH_NAME.eq(k.GRAPH_NAME), kr.COORDINATE.eq(k.COORDINATE), kr.CALL.eq(k.CALL),
                    kr.METHOD_SOURCE_NAME.eq(k.METHOD_SOURCE_NAME), kr.METHOD_CLASS_NAME.eq(k.METHOD_CLASS_NAME),
                    kr.METHOD_NAME.eq(k.METHOD_NAME), kr.METHOD_DESCRIPTOR.eq(k.METHOD_DESCRIPTOR)))
            .execute();
        OccurrenceProducers.refresh(dsl, k, kr, graph, null, touchedAt, noCondition());
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_RULE, graph, null,
            touchedAt, GRAPHITRON_OCCURRENCE_CALL_RULE.CALL_KIND.eq("CONDITION"));
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE,
            graph, null, touchedAt, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE.CALL_KIND.eq("CONDITION"));
        // Every condition call is stated by now, so one this reading did not mark is stale; it goes
        // before the conditions and the fills read the method it pinned last time.
        OccurrenceProducers.sweepCalls(dsl, graph, touchedAt, "CONDITION");
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CONDITION, GRAPHITRON_OCCURRENCE_CONDITION_RULE,
            graph, null, touchedAt, noCondition());
        OccurrenceCallParameters.writeConditions(dsl, graph, touchedAt);
    }

    /** Deletes the conditions and the condition calls' fills this reading did not mark. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        OccurrenceCallParameters.sweep(dsl, graph, touchedAt, "CONDITION");
        var c = GRAPHITRON_OCCURRENCE_CONDITION;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
