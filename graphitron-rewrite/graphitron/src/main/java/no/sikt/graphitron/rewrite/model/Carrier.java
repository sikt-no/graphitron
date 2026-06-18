package no.sikt.graphitron.rewrite.model;

import java.util.Objects;

/**
 * <strong>Retiring (R316 slice 2 → slice 4).</strong> The {@code carrier} axis is superseded by the
 * {@link Source} arrival wrapper; {@link OutputField#carrier()} now derives this value from
 * {@link OutputField#source()} as an additive-cutover bridge so the R281 corpus keeps classifying
 * unchanged. This type and {@link SourceCardinality} retire when slice 4 migrates the corpus harness
 * and the {@code @classified} directive onto {@code source()}.
 *
 * <p>The {@code carrier} dimension (R299): the GraphQL parent-type category a field is defined on, which
 * <em>is</em> its field type. The carrier is position, and it is also the legality gate the retired
 * {@code producer} axis used to imply: write intents only on {@link Mutation}, {@code NodeResolve}
 * only on {@link Query}, {@code Nesting} only on {@link Source}. The carrier is materialised on the
 * field model by {@link OutputField#carrier()}, defaulted per carrier root ({@link QueryField} →
 * {@link Query}, {@link MutationField} → {@link Mutation}, {@link ChildField} → {@link Source}), so
 * an off-carrier {@link Intent} is unrepresentable.
 *
 * <p>R305 reverses the earlier "flat typed enum, carries no per-value payload" decision: the
 * {@link Source} arm now carries a {@link SourceShape} (what arrives at {@code env.getSource()}) and
 * a {@link SourceCardinality} (how many source objects arrive). These are load-bearing forks
 * (re-fetch derivation, the DataLoader-skip optimisation, list-ordering determinism), so they earn
 * type-system representation rather than staying smeared across leaf identity and {@code SourceKey},
 * per "sealed hierarchies over enums for typed information". {@link Query} and {@link Mutation} have
 * no source and therefore no payload; the sealed split makes "a Query has no source-shape"
 * unrepresentable rather than a convention.
 *
 * <p>The R281/R299 corpus mirrors the carrier <em>category</em> SDL-side (the
 * {@code @classified(carrier:)} directive argument: {@code Query} / {@code Mutation} / {@code Source})
 * and the {@link Source}-arm payload through the sibling {@code sourceShape:} / {@code sourceCardinality:}
 * directive arguments, asserting the whole value by sealed-arm structural equality against
 * {@link OutputField#carrier()}.
 *
 * <p>See the {@code carrier} axis in R222's "Field-side dimensional model (refined 2026-06-13)".
 */
public sealed interface Carrier permits Carrier.Query, Carrier.Mutation, Carrier.Source {

    /** The root {@code Query} type ({@link QueryField} leaves). No source. */
    record Query() implements Carrier {}

    /** The root {@code Mutation} type ({@link MutationField} leaves). No source. */
    record Mutation() implements Carrier {}

    /**
     * Every other (non-Subscription) type ({@link ChildField} leaves; the {@code Source}-carried
     * field). Carries the source-side shape and cardinality of what arrives at
     * {@code env.getSource()}.
     */
    record Source(SourceShape shape, SourceCardinality cardinality) implements Carrier {
        public Source {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(cardinality, "cardinality");
        }
    }
}
