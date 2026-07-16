package no.sikt.graphitron.rewrite.model;

/**
 * A composite type's <em>arrival cardinality</em>: how many source objects of a parent type
 * reach a nested field's fetcher in one request. The lattice is {@code One < Many} with {@code Many}
 * absorbing; it is the arrival half of the wrapper algebra, whose monoid has {@code Root} as
 * the empty product, {@link #ONE} the identity, and {@link #MANY} the absorber.
 *
 * <p>This is the input to {@link OutputField#source(Arrival)}: a nested field on a parent whose
 * arrival is {@link #ONE} declares {@link Source.OnlyChild} (single arrival, direct SQL), else
 * {@link Source.Child} (the many-arrival DataLoader-batched absorber). Root fields ignore it (the
 * empty product needs no ancestor fact). Arrival is a function of the <em>parent typename alone</em>
 * (every field on one parent folds the same way), computed once as a typename-keyed index over the
 * assembled SDL, so it is not stored per-leaf; see {@code ArrivalIndex} and
 * {@link no.sikt.graphitron.rewrite.GraphitronSchema#sourceOf sourceOf}.
 *
 * <p>Distinct from {@link Arity}, the <em>local</em> delivery arity of one endpoint (a producer's
 * return, a carrier field's wrapper): {@code Arrival} is the accumulated ancestor product with the
 * {@link #tensor(Arrival)} monoid; an {@code Arity} never accumulates.
 */
public enum Arrival {
    /** Exactly one source object arrives (the wrapper-algebra identity). */
    ONE,
    /** Many source objects arrive (the absorbing element). */
    MANY;

    /**
     * The arrival monoid's product: {@link #MANY} absorbs, {@link #ONE} is the identity. Used to fold
     * a parent's arrival with a reaching field's list-ness ({@code Many} for a list wrapper,
     * {@code One} otherwise).
     */
    public Arrival tensor(Arrival other) {
        return (this == MANY || other == MANY) ? MANY : ONE;
    }
}
