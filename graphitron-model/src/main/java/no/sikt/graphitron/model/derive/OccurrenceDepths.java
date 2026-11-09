package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.jooq.impl.DSL.max;

/**
 * What each occurrence executes, where its values come from, the types it must reach and the path
 * to each, one depth at a time, then the sweep of every relation: one stage of the graphitron
 * gatherer's anchor phase, since each depth reads what the depth above it wrote.
 *
 * <p>Within a depth: the producers and the methods their calls pin; what fills each parameter of
 * those calls, whose pin the fills decide; what the producers produce, which reads the pinned
 * methods; the sources, which read the producers, its tables or its class;
 * the needs off those sources; the accessor paths meeting the needs that start on an object, where a
 * child's object source at the next depth begins; the paths no claim covers, an empty one where the need starts on its
 * landing and an inferred one over the one foreign key; and the claimed needs walked into their
 * steps, each to its written or inherited path or to a stop, {@link Steps} writing the defects of
 * the walk as it takes it. A child field or an argument under a field that writes a path starts
 * where that path's join path lands, so a depth reads the join paths of the depth above.
 */
public final class OccurrenceDepths {

    private OccurrenceDepths() {}

    /** Writes every depth of {@code graph}'s occurrences, then sweeps what no depth marked. */
    public static void derive(DSLContext dsl, String graph, LocalDateTime touchedAt) {
        var o = GRAPHITRON_OCCURRENCE;
        Integer deepest = dsl.select(max(o.DEPTH)).from(o)
            .where(o.GRAPH_NAME.eq(graph)).and(o.TOUCHED_AT.eq(touchedAt))
            .fetchOne(0, Integer.class);
        if (deepest != null) {
            for (int depth = 0; depth <= deepest; depth++) {
                OccurrenceProducers.writeDepth(dsl, graph, depth, touchedAt);
                OccurrenceCallParameters.writeDepth(dsl, graph, depth, touchedAt);
                OccurrenceProducers.writeResults(dsl, graph, depth, touchedAt);
                OccurrenceSources.writeDepth(dsl, graph, depth, touchedAt);
                PathNeeds.writeDepth(dsl, graph, depth, touchedAt);
                AccessorPaths.writeDepth(dsl, graph, depth, touchedAt);
                Paths.writeDepth(dsl, graph, depth, touchedAt);
                Steps.writeDepth(dsl, graph, depth, touchedAt);
            }
        }
        OccurrenceCallParameters.sweep(dsl, graph, touchedAt);
        OccurrenceProducers.sweep(dsl, graph, touchedAt);
        OccurrenceSources.sweep(dsl, graph, touchedAt);
        PathNeeds.sweep(dsl, graph, touchedAt);
        Paths.sweep(dsl, graph, touchedAt);
        Steps.sweep(dsl, graph, touchedAt);
    }
}
