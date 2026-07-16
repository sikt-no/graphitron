package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * The {@code source} dimension: the field's <em>arrival endpoint</em>, modeled as a wrapper
 * around a {@link SourceShape}. The wrapper is the field's <em>arrival cardinality</em> (how many
 * source objects reach its fetcher), and it is the emit-strategy dispatch: {@link Child} batches
 * through a DataLoader, {@link Root} and {@link OnlyChild} run their SQL directly (single invocation).
 *
 * <p>This folds the retired {@code carrier} axis and the retired stand-alone source-cardinality
 * enum into one sealed hierarchy: the {@code One} / {@code Many} cardinality becomes the
 * {@link OnlyChild} / {@link Child} arm identity, and {@code Zero} (no source) is {@link Root}'s
 * shape-absence. Naming the arms for the arrival (not a bare {@code One} / {@code Many})
 * keeps the count from being misread as the field's <em>output</em> arity: the same {@code {One, Many}}
 * values sit on the target wrapper, and cardinality only ever exists as a wrapper bound to an endpoint
 * (the wrapper algebra).
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
 * <p>The true ancestor-product arrival ({@link Arrival}, computed once as a typename-keyed
 * index over the assembled SDL) is folded in and threaded into {@link OutputField#source(Arrival)}: a
 * {@link ChildField} on a {@link Arrival#ONE} parent declares {@link OnlyChild}, else the {@link Child}
 * absorber. {@link SourceShape} stays the shape wrapped by the nested arms; its internal reshaping (the
 * reflected {@code Record} facts) is the downstream {@code SourceKey} work. The {@code carrier} axis was
 * retired and the classification corpus migrated onto {@link OutputField#source(Arrival)}, so this is
 * now the sole arrival-axis primitive.
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
 * invocation, no DataLoader. Reached when the parent type's ancestor-product arrival folds
     * to {@link Arrival#ONE} (a single non-list chain down from an operation root, no {@code @node} /
     * {@code @key} seed and no fan-in).
     *
     * <p><strong>Honesty clause.</strong> {@code One} is a static per-dispatch guarantee about
     * unaliased projections. Query aliases can materialize {@code k} parent instances even on a
     * {@code One} chain, so any emit strategy {@code OnlyChild} ever licenses must stay row-correct at
     * every arrival count: direct SQL once per invocation, degrading in query count, never in rows.
     * {@link Child} stays the absorbing always-correct arm. The current emitters keep leaf-identity
     * dispatch (an {@code OnlyChild}-classified batch field still emits its DataLoader, a one-element
     * batch), so populating this arm changes no generated code. The reentry re-platforming stays
     * arrival-uniform by decision; the direct-SQL {@code OnlyChild} emit and this clause's enforcer
     * are deferred to future work.
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
