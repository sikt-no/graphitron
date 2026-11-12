package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Table;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_RULE;
import static no.sikt.graphitron.model.derive.OccurrenceProducers.atDepth;
import static org.jooq.impl.DSL.noCondition;

/**
 * How each child producer's keys are filled from its source: the members of the source's class that
 * could hand a producer its keys, a depth at a time inside {@link OccurrenceDepths} after the depth's
 * sources, whose object names the class, since the accessor paths read them; then, in
 * {@link OccurrenceBindings}, the key each producer is filled with and the member an
 * {@code ACCESSOR} fill reads through, a selection's batch key and a service's key parameter alike.
 *
 * <p>Rows an earlier reading wrote go before they are written, so a member or a one-key path this
 * reading no longer states cannot feed a lift before the sweep reaches it.
 */
public final class OccurrenceLifts {

    private OccurrenceLifts() {}

    /** The members that could hand each producer at {@code depth} its keys. */
    public static void writeCandidates(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE_RULE,
            graph, depth, touchedAt);
    }

    /** The keys the producers are filled with, and their members. */
    public static void write(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        // The lift before the member that hangs on it; clearing the lift takes the member with it.
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_RULE,
            graph, null, touchedAt);
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR_RULE,
            graph, null, touchedAt);
    }

    /** Deletes the candidates of the occurrences at {@code depth}, or of all where null, this reading did not mark. */
    public static void sweepCandidates(DSLContext dsl, String graph, Integer depth, LocalDateTime touchedAt) {
        var t = GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE;
        dsl.deleteFrom(t)
            .where(t.GRAPH_NAME.eq(graph))
            .and(t.TOUCHED_AT.ne(touchedAt))
            .and(atDepth(t.GRAPH_NAME, t.COORDINATE, depth))
            .execute();
    }

    private static void write(DSLContext dsl, Table<?> target, Table<?> rule, String graph, Integer depth,
                              LocalDateTime touchedAt) {
        var targetGraph = target.field("GRAPH_NAME", String.class);
        dsl.deleteFrom(target)
            .where(targetGraph.eq(graph))
            .and(atDepth(targetGraph, target.field("COORDINATE", String.class), depth))
            .and(target.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
            .execute();
        OccurrenceProducers.refresh(dsl, target, rule, graph, depth, touchedAt, noCondition());
    }
}
