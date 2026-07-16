package no.sikt.graphitron.rewrite.model;

/**
 * Capability interface for the three field variants that are backed by {@code @lookupKey}
 * arguments and generate a VALUES + JOIN lookup query: {@link QueryField.QueryLookupTableField}
 * (root synchronous lookup), {@link ChildField.LookupTableField} (table-mapped parent, inline
 * correlated subquery), and {@link ChildField.BatchedLookupTableField} (DataLoader-backed, both
 * parent backings, gated on its stored {@code SourceShape}).
 *
 * <p>Per the "Capabilities reify an orthogonal axis; sealed switches fork on identity" principle
 * in {@code docs/architecture/explanation/development-principles.adoc}, the generator routes on this
 * capability rather than on a per-variant {@code instanceof} chain. See
 * {@code docs/architecture/reference/argument-resolution.adoc} for the design.
 *
 * <p>{@link #lookupMapping()} is always non-null for these variants: lookups are defined by the
 * presence of {@code @lookupKey} args, which always resolve to a {@link LookupMapping.ColumnMapping}
 * with at least one arg. Lookup-key NodeId folds onto {@code ColumnMapping} with
 * {@code NodeIdDecodeKeys.ThrowOnMismatch}. Implementations declare the component directly
 * (narrow-component-types principle) rather than wrapping in {@code Optional}.
 */
public sealed interface LookupField permits
    QueryField.QueryLookupTableField,
    ChildField.LookupTableField,
    ChildField.BatchedLookupTableField {

    LookupMapping lookupMapping();
}
