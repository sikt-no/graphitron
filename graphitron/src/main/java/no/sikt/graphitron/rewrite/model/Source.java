package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The {@code source} dimension (R316): the field's <em>arrival endpoint</em>, modeled as a wrapper
 * around a {@link SourceShape}. The wrapper is the field's <em>arrival cardinality</em> (how many
 * source objects reach its fetcher), and it is the emit-strategy dispatch: {@link Child} batches
 * through a DataLoader, {@link Root} and {@link OnlyChild} run their SQL directly (single invocation).
 *
 * <p>This folds the retired {@code carrier} axis (R299) and the retired stand-alone source-cardinality
 * enum into one sealed hierarchy: the {@code One} / {@code Many} cardinality becomes the
 * {@link OnlyChild} / {@link Child} arm identity, and {@code Zero} (no source) is {@link Root}'s
 * shape-absence. Naming the arms for the arrival (not a bare {@code One} / {@code Many})
 * keeps the count from being misread as the field's <em>output</em> arity: the same {@code {One, Many}}
 * values sit on the target wrapper, and cardinality only ever exists as a wrapper bound to an endpoint
 * (the wrapper algebra, R316).
 *
 * <ul>
 *   <li>{@link Root} (permits {@link Root.Query} / {@link Root.Mutation}) — an operation root; no
 *       source object arrives (the empty product). The {@code Query} / {@code Mutation} split is the
 *       operation-legality gate (writes only on {@code Mutation}, {@code NodeResolve} only on
 *       {@code Query}), so {@link Root} carries no {@link SourceShape}.</li>
 *   <li>{@link OnlyChild} — exactly one source object arrives (arrival {@code One}); direct SQL.</li>
 *   <li>{@link Child} — many source objects arrive (arrival {@code Many}); DataLoader-batched.</li>
 * </ul>
 *
 * <p>R316 slice 2 builds {@link Child} for every {@link ChildField} (R305 conservatively hard-codes the
 * {@code Many} absorbing element until R463 computes the true ancestor-product fold); {@link OnlyChild}
 * is producible but conservatively unreached. {@link SourceShape} stays the shape wrapped by the nested
 * arms; its internal reshaping (the reflected {@code Record} facts) is the downstream {@code SourceKey}
 * work, not this slice. R316 slice 4 retired the {@code carrier} axis and migrated the R281 corpus onto
 * {@link OutputField#source()}, so this is now the sole arrival-axis primitive.
 *
 * <p>See R316 ("Pivot the field-dimensional model to (source, operation, target)") and the {@code source}
 * axis in R222's "Field-side dimensional model".
 */
public sealed interface Source permits Source.Root, Source.OnlyChild, Source.Child {

    /**
     * An operation root: no source object arrives (the empty product). The {@code Query} /
     * {@code Mutation} split is the operation-legality gate, so it is itself a sealed sub-hierarchy
     * rather than a flag, making an off-root operation unrepresentable.
     */
    sealed interface Root extends Source permits Root.Query, Root.Mutation {

        /** The root {@code Query} type ({@link QueryField} leaves). */
        record Query() implements Root {}

        /** The root {@code Mutation} type ({@link MutationField} leaves). */
        record Mutation() implements Root {}
    }

    /**
     * Exactly one source object arrives (arrival {@code One}): the field's SQL runs directly, single
     * invocation, no DataLoader. Producible but conservatively unreached until R463 computes the
     * true ancestor-product cardinality that would let a field declare it (R305 hard-codes {@link Child}).
     */
    record OnlyChild(SourceShape shape) implements Source {
        public OnlyChild {
            Objects.requireNonNull(shape, "shape");
        }
    }

    /**
     * Many source objects arrive (arrival {@code Many}): the field batches through a DataLoader or it is
     * an N+1. The arm every {@link ChildField} builds today.
     */
    record Child(SourceShape shape) implements Source {
        public Child {
            Objects.requireNonNull(shape, "shape");
        }
    }
}
