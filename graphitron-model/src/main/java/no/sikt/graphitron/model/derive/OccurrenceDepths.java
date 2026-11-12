package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;

import java.time.LocalDateTime;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_OCCURRENCE;
import static org.jooq.impl.DSL.max;

/**
 * Where each occurrence's values come from, the types it must reach and the path to each, one depth
 * at a time, then the sweep of every relation it writes: one stage of the graphitron gatherer's anchor
 * phase, since each depth reads what the depth above it wrote. A child field or an argument under a
 * field that writes a path starts where that path's join path lands, so a depth reads the join
 * paths, sources and needs of the depth above.
 *
 * <p>Only that recursion runs here. The producers, their service calls' fills and what they produce
 * read nothing a depth writes, so {@link OccurrenceProducers} states them for the whole graph before
 * this stage; what each occurrence binds once its path is known, the lifts, node ids, columns, tables
 * and {@code @condition} calls, nothing here reads, so {@link OccurrenceBindings} states them after.
 *
 * <p>Within a depth: the sources, which read the producers and the parent's source, tables, accessor
 * path or join path; the members that could hand each producer its keys; the needs off those sources;
 * the accessor paths meeting the needs that start on an object, where a child's object source at the
 * next depth begins; the paths no claim covers, an empty one where the need starts on its landing and
 * an inferred one over the one foreign key; and the claimed needs walked into their steps, each to its
 * written or inherited path or to a stop, {@link Steps} writing the defects of the walk as it takes it.
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
                OccurrenceSources.writeDepth(dsl, graph, depth, touchedAt);
                OccurrenceLifts.writeCandidates(dsl, graph, depth, touchedAt);
                PathNeeds.writeDepth(dsl, graph, depth, touchedAt);
                AccessorPaths.writeDepth(dsl, graph, depth, touchedAt);
                Paths.writeDepth(dsl, graph, depth, touchedAt);
                Steps.writeDepth(dsl, graph, depth, touchedAt);
            }
        }
        OccurrenceLifts.sweepCandidates(dsl, graph, touchedAt);
        OccurrenceSources.sweep(dsl, graph, touchedAt);
        PathNeeds.sweep(dsl, graph, touchedAt);
        Paths.sweep(dsl, graph, touchedAt);
        Steps.sweep(dsl, graph, touchedAt);
    }
}
