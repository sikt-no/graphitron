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
 * <p>Source and read surface are independent: a chain-sourced read carries filters and ordering
 * resolved against the chain terminus, exactly as an anchor-sourced one does. Two facts are
 * still pinned in {@code QueryTableField}'s compact constructor, each against the axis that owns
 * it: {@code @lookupKey} over a chain is a classify-time typed deferral (the key tuple it joins
 * on is a terminus primary key, which a routine result has none of), and the chain's terminus is
 * the field's {@code @table} type.
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
