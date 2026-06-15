package no.sikt.graphitron.rewrite.model;

/**
 * The <em>source</em> cardinality of a {@link Carrier.Source}-carried field: how many source
 * objects arrive at the field across a single resolver dispatch (R305). It is the product of all
 * ancestor field cardinalities along the path from the operation root to the field, over the
 * {@code {One, Many}} semiring where {@link #One} is the identity and {@link #Many} absorbs
 * ({@code One x One = One}, {@code One x Many = Many}, {@code Many x Many = Many}): a field is
 * {@link #One} only when every ancestor field is single-valued, and {@link #Many} the moment any one
 * ancestor is list-valued. A path-accumulated property of where the field sits in the selection tree,
 * read off the field's ancestry, not a local property of the field's own return type or wrapper.
 *
 * <p>This is distinct from {@link SourceKey.Cardinality}, which is the <em>per-key</em>, target-side
 * count (how many source rows the rows-method body yields for a single DataLoader key). The two
 * answer different questions and vary independently; {@code Source} cardinality must not be derived
 * from {@link SourceKey#cardinality()}.
 *
 * <p>R305 builds only the {@link #One} path: the {@link #Many} arrival derivation (a list-valued
 * ancestor, which needs the producing field's ancestry walk) is R308's
 * ({@code service-list-payload-arrival}) follow-up. {@link #Many} is declared here so R308 is a
 * producer-only change rather than a retrofit; until then no classifier path constructs it on the
 * record-source arm.
 */
public enum SourceCardinality {
    /** Every ancestor field is single-valued: one source object arrives. */
    One,
    /** Some ancestor field is list-valued: many source objects arrive (R308 derives this). */
    Many
}
