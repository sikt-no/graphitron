package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The resolved FROM-source axis of a root table read: {@link None} when the read starts at the
 * return type's own catalog table, {@link Chain} when it starts at a database routine
 * ({@code @routine}) whose table chain the {@link RoutineChain} carrier pins. This is the
 * source-side tableExpr fact of {@link QueryField.QueryTableField}: routine-sourced-ness is a
 * source axis over the surviving root read leaf rather than a leaf identity, matching the child
 * side, where a routine chain has always ridden the shared table-read leaves as
 * {@link TableExpr.RoutineCall} hops in {@code joinPath()}.
 *
 * <p>The {@link Chain} arm's shipped regime is pinned where source and read surface meet, in
 * {@code QueryTableField}'s compact constructor: a chain-sourced read carries no filters, no
 * ordering, no pagination and no lookup ({@code @condition} / {@code @orderBy} / connection
 * shapes / {@code @lookupKey} on a routine chain are classify-time typed rejections), and the
 * chain's terminus is the field's {@code @table} type.
 */
public sealed interface RoutineResolution {

    /** The read's FROM is the return type's own catalog table; no routine is involved. */
    record None() implements RoutineResolution {
        public static final None INSTANCE = new None();
    }

    /** The read's FROM starts at a routine call; the chain carries the start and its hops. */
    record Chain(RoutineChain chain) implements RoutineResolution {
        public Chain {
            Objects.requireNonNull(chain, "chain");
        }
    }
}
