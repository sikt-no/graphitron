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
 * Writes the {@code @condition} calls the occurrences at a depth make, one per table each binds: the
 * candidate overloads into {@code graphitron_occurrence_call_candidate}, the call and the method it
 * pins, {@code graphitron_occurrence_condition} and the call's fills, each from the condition's own
 * rule view, marked with this reading.
 *
 * <p>The last step of a depth inside {@link OccurrenceDepths}, after {@link OccurrenceTables}: which
 * tables a condition is applied to is the depth's bindings, so its call waits on them. The calls,
 * candidates and methods are swept with every call's by {@link OccurrenceProducers#sweep}, the fills
 * by {@link OccurrenceCallParameters#sweep}; {@link #sweep} takes the subtype.
 */
public final class OccurrenceConditions {

    private OccurrenceConditions() {}

    /** States the {@code @condition} calls of the occurrences at {@code depth} and what fills them. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        var k = GRAPHITRON_OCCURRENCE_CALL_CANDIDATE;
        var kr = GRAPHITRON_OCCURRENCE_CONDITION_CANDIDATE_RULE;
        // A candidate this reading no longer states goes before the pin reads them.
        dsl.deleteFrom(k)
            .where(k.GRAPH_NAME.eq(graph), k.CALL_KIND.eq("CONDITION"))
            .and(OccurrenceProducers.atDepth(k.GRAPH_NAME, k.COORDINATE, depth))
            .andNotExists(selectOne().from(kr)
                .where(kr.GRAPH_NAME.eq(k.GRAPH_NAME), kr.COORDINATE.eq(k.COORDINATE), kr.CALL.eq(k.CALL),
                    kr.METHOD_SOURCE_NAME.eq(k.METHOD_SOURCE_NAME), kr.METHOD_CLASS_NAME.eq(k.METHOD_CLASS_NAME),
                    kr.METHOD_NAME.eq(k.METHOD_NAME), kr.METHOD_DESCRIPTOR.eq(k.METHOD_DESCRIPTOR)))
            .execute();
        OccurrenceProducers.refresh(dsl, k, kr, graph, depth, touchedAt, noCondition());
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL, GRAPHITRON_OCCURRENCE_CALL_RULE, graph, depth,
            touchedAt, GRAPHITRON_OCCURRENCE_CALL_RULE.CALL_KIND.eq("CONDITION"));
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CALL_METHOD, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE,
            graph, depth, touchedAt, GRAPHITRON_OCCURRENCE_CALL_METHOD_RULE.CALL_KIND.eq("CONDITION"));
        OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_CONDITION, GRAPHITRON_OCCURRENCE_CONDITION_RULE,
            graph, depth, touchedAt, noCondition());
        OccurrenceCallParameters.writeConditions(dsl, graph, depth, touchedAt);
    }

    /** Deletes the conditions no depth of this reading marked. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var c = GRAPHITRON_OCCURRENCE_CONDITION;
        dsl.deleteFrom(c)
            .where(c.GRAPH_NAME.eq(graph))
            .and(c.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
