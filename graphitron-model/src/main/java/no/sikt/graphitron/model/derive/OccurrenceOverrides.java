package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_OVERRIDDEN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE_OVERRIDDEN_RULE;
import static org.jooq.impl.DSL.max;
import static org.jooq.impl.DSL.noCondition;

/**
 * The {@code occurrence-overrides} stage: which occurrences sit at or under a {@code @condition}
 * written with {@code override: true}, into {@code graphitron_occurrence_overridden}, a depth at a
 * time, an argument or input field taking its parent's row. Off the occurrences and the directive
 * applications alone, so it runs before {@link OccurrenceDepths}.
 */
public final class OccurrenceOverrides {

    private OccurrenceOverrides() {}

    /** Writes every depth's rows of {@code graph}, then sweeps what no depth marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        Integer deepest = dsl.select(max(o.DEPTH)).from(o)
            .where(o.GRAPH_NAME.eq(graph)).and(o.TOUCHED_AT.eq(touchedAt))
            .fetchOne(0, Integer.class);
        // A field may carry the override itself, at depth 0.
        if (deepest != null) {
            for (int depth = 0; depth <= deepest; depth++) {
                OccurrenceProducers.refresh(dsl, GRAPHITRON_OCCURRENCE_OVERRIDDEN,
                    GRAPHITRON_OCCURRENCE_OVERRIDDEN_RULE, graph, depth, touchedAt, noCondition());
            }
        }
        var l = GRAPHITRON_OCCURRENCE_OVERRIDDEN;
        dsl.deleteFrom(l)
            .where(l.GRAPH_NAME.eq(graph))
            .and(l.TOUCHED_AT.ne(touchedAt))
            .execute();
    }
}
