package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Table;

import java.time.LocalDateTime;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_ONE_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_JOIN_PATH_ONE_KEY_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE_RULE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_RULE;
import static no.sikt.graphitron.model.derive.OccurrenceProducers.atDepth;
import static org.jooq.impl.DSL.noCondition;

/**
 * How each child producer's query restarts from its source, stated per depth inside
 * {@link OccurrenceDepths}: the members of the source's class that could hand a producer its keys,
 * after the depth's sources, whose object names the class; then, after the depth's join paths, the
 * paths that ride one key in one step, the lift and the member an {@code ACCESSOR} lift reads
 * through. The rule is on {@code code-generation-triggers.adoc} § Producer.
 *
 * <p>A depth's rows an earlier reading wrote go before it is written, so a member or a one-key path
 * this reading no longer states cannot feed the depth's lift before the sweep reaches it.
 */
public final class OccurrenceLifts {

    private OccurrenceLifts() {}

    /** The members that could hand each producer at {@code depth} its keys. */
    public static void writeCandidates(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE_RULE,
            graph, depth, touchedAt);
    }

    /** The one-key paths, the lifts and their members, for the occurrences at {@code depth}. */
    public static void writeDepth(DSLContext dsl, String graph, int depth, LocalDateTime touchedAt) {
        write(dsl, GRAPHITRON_JOIN_PATH_ONE_KEY, GRAPHITRON_JOIN_PATH_ONE_KEY_RULE, graph, depth, touchedAt);
        // The lift before the member that hangs on it; clearing the lift takes the member with it.
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_RULE,
            graph, depth, touchedAt);
        write(dsl, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR_RULE,
            graph, depth, touchedAt);
    }

    /** Deletes the rows no depth of this reading marked, the member before the lift it hangs on. */
    public static void sweep(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        for (Table<?> t : List.of(GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_ACCESSOR, GRAPHITRON_OCCURRENCE_PRODUCER_LIFT,
                GRAPHITRON_OCCURRENCE_PRODUCER_LIFT_CANDIDATE, GRAPHITRON_JOIN_PATH_ONE_KEY)) {
            dsl.deleteFrom(t)
                .where(t.field("GRAPH_NAME", String.class).eq(graph))
                .and(t.field("TOUCHED_AT", LocalDateTime.class).ne(touchedAt))
                .execute();
        }
    }

    private static void write(DSLContext dsl, Table<?> target, Table<?> rule, String graph, int depth,
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
