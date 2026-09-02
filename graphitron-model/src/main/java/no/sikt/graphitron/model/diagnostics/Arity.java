package no.sikt.graphitron.model.diagnostics;

/**
 * The local delivery arity of one endpoint: does it deliver a single value or a collection?
 * Always carried as a component of the fact that names its endpoint — the producer-side bindings
 * ({@code ProducerBinding.DmlEmitted#arrival()}, {@code ProducerBinding.ServiceEmitted#arrival()}),
 * the {@code @service} carrier shape verdict
 * ({@code BuildContext.ServiceCarrierShape.Coherent} and the {@link ServiceCarrierShapeError}
 * arms, where the disagreeing endpoints' arities are the typed payload), and the per-carrier-field
 * producer-arrival memo at the reflection boundary ({@code RecordBindingResolver}). Never a
 * detached slot: per {@code Target}'s wrapper-algebra principle, a free one/many value is
 * ambiguous between the two ends of a field's edge.
 *
 * <p>Distinct from {@code Arrival}, the <em>accumulated</em> ancestor-product arrival
 * cardinality with its {@code tensor} monoid, keyed by parent typename: an {@code Arity} is a
 * site-local wrapper fact and never accumulates. (This vocabulary is the surviving half of
 * the retired {@code SourceKey.Cardinality}; the key-side half dissolved into the
 * {@code KeyLift} arms and the field wrapper positions.)
 */
public enum Arity {
    /** The endpoint delivers a single value ({@code XRecord}, one payload, one row). */
    ONE,
    /** The endpoint delivers a collection ({@code List<XRecord>}, {@code [Payload]}, a Result). */
    MANY
}
