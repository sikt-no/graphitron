package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The {@code source} dimension: the field's <em>arrival endpoint</em>, modeled as a wrapper
 * around a {@link SourceShape}. The wrapper is the field's <em>arrival cardinality</em> (how many
 * source objects reach its fetcher), and it is the emit-strategy dispatch: {@link Child} batches
 * through a DataLoader, {@link Root} and {@link OnlyChild} run their SQL directly (single invocation).
 *
 * <p>The arms are named for the arrival rather than a bare {@code One} / {@code Many} so the count
 * is not misread as the field's <em>output</em> arity: the same {@code {One, Many}} values sit on
 * the target wrapper, and cardinality only ever exists as a wrapper bound to an endpoint (the
 * wrapper algebra). This is the sole arrival-axis primitive.
 *
 * <p>The ancestor-product arrival ({@link Arrival}, computed once as a typename-keyed index over
 * the assembled SDL) is threaded into {@link OutputField#source(Arrival)}: a {@link ChildField} on
 * an {@link Arrival#ONE} parent declares {@link OnlyChild}, else the {@link Child} absorber.
 * {@link SourceShape} is the shape wrapped by the nested arms; its internal reshaping (the
 * reflected {@code Record} facts) is downstream {@link SourceKey} work.
 */
public sealed interface Source permits Source.Root, Source.OnlyChild, Source.Child {

    /**
     * An operation root: no source object arrives (the empty product), so no {@link SourceShape}
     * is carried. The {@code Query} / {@code Mutation} split is the operation-legality gate
     * (writes only on {@code Mutation}, {@code NodeResolve} only on {@code Query}); it is a sealed
     * sub-hierarchy rather than a flag, making an off-root operation unrepresentable.
     */
    sealed interface Root extends Source permits Root.Query, Root.Mutation {

        /** The root {@code Query} type ({@link QueryField} leaves). */
        record Query() implements Root {}

        /** The root {@code Mutation} type ({@link MutationField} leaves). */
        record Mutation() implements Root {}
    }

    /**
     * Exactly one source object arrives (arrival {@code One}): the field's SQL runs directly,
     * single invocation, no DataLoader. Reached when the parent type's ancestor-product arrival
     * folds to {@link Arrival#ONE} (a single non-list chain down from an operation root, no
     * {@code @node} / {@code @key} seed and no fan-in).
     *
     * <p><strong>Honesty clause.</strong> {@code One} is a static per-dispatch guarantee about
     * unaliased projections. Query aliases can materialize {@code k} parent instances even on a
     * {@code One} chain, so any emit strategy {@code OnlyChild} ever licenses must stay row-correct at
     * every arrival count: direct SQL once per invocation, degrading in query count, never in rows.
     * {@link Child} stays the absorbing always-correct arm. The current emitters keep leaf-identity
     * dispatch (an {@code OnlyChild}-classified batch field still emits its DataLoader, a one-element
     * batch), so populating this arm changes no generated code; that half is pinned by
     * {@code ArrivalUniformEmitPinTest} (zero {@code OnlyChild} dispatch sites across the
     * generators). The row-correctness constraint above is the forward burden: a strategy that
     * starts consuming the arm discharges it with its own aliasing enforcer and retires the pin.
     */
    record OnlyChild(SourceShape shape) implements Source {
        public OnlyChild {
            Objects.requireNonNull(shape, "shape");
        }
    }

    /**
     * Many source objects arrive (arrival {@code Many}): the field batches through a DataLoader or it is
     * an N+1. The absorbing element of the arrival monoid: a {@link ChildField} folds to this arm
     * whenever the parent type carries a {@code @node} / {@code @key} seed, is reached by more than one
     * field edge (fan-in or recursion), or sits below a list ancestor.
     */
    record Child(SourceShape shape) implements Source {
        public Child {
            Objects.requireNonNull(shape, "shape");
        }
    }
}
