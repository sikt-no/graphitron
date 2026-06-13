package no.sikt.graphitron.rewrite.classifieddsl;

/**
 * The three-axis classification verdict R299's corpus asserts: a {@link Carrier} (the GraphQL
 * parent-type category, which <em>is</em> the field type), an {@link Intent} (the operation kind), and
 * a {@link Mapping} (the value shape, carrying build-vs-consume). This is the dimensional fingerprint
 * the {@code @classified} directive carries and the {@link LeafTupleAdapter} produces from today's
 * sealed leaves.
 *
 * <p>The R281 {@code (producer, mapping)} verdict retired here: the {@code producer} dimension
 * dissolves, its information redistributing across {@code carrier} (position), {@code intent}
 * (operation), {@code mapping} (build-vs-consume), and a <em>derived layer</em> (re-fetch, new-query,
 * {@code FetchRelated}, polarity) that is computed from the axes and slots, never asserted as a
 * coordinate. See R222's "Field-side dimensional model (refined 2026-06-13)".
 *
 * <p>The tuple is the primary fingerprint, not the complete emit key: the derived facts and the
 * orthogonal slots (source-context, fetcher/loader mechanism, dispatch batching, error channel) live
 * beside these three axes, so two leaves differing only in a slot share one tuple.
 */
public record DimensionTuple(Carrier carrier, Intent intent, Mapping mapping) {
}
