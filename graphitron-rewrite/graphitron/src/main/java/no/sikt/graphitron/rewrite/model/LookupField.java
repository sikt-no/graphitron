package no.sikt.graphitron.rewrite.model;

/**
 * Capability interface for the four field variants that are backed by {@code @lookupKey}
 * arguments and generate a VALUES + JOIN lookup query: {@link QueryField.QueryLookupTableField}
 * (root synchronous lookup), {@link ChildField.LookupTableField} (table-mapped parent, inline
 * correlated subquery), {@link ChildField.SplitLookupTableField} (table-mapped parent,
 * DataLoader-backed), and {@link ChildField.RecordLookupTableField} (result-mapped parent,
 * DataLoader-backed).
 *
 * <p>Per the "Capability interfaces and sealed switches serve different roles" principle in
 * {@code graphitron-rewrite/docs/rewrite-design-principles.adoc}, the generator routes on this
 * capability rather than on a per-variant {@code instanceof} chain. See
 * {@code graphitron-rewrite/docs/argument-resolution.adoc} for the design.
 *
 * <p>{@link #lookupMapping()} is always non-null for these variants — lookups are defined by the
 * presence of {@code @lookupKey} args, which always resolve to a {@link LookupMapping.ColumnMapping}
 * with at least one arg (post-R50 phase (f-D); the legacy {@code NodeIdMapping} arm retired
 * with the lift, lookup-key NodeId folds onto {@code ColumnMapping} with
 * {@code NodeIdDecodeKeys.ThrowOnMismatch}).
 * Implementations declare the component directly (narrow-component-types principle) rather
 * than wrapping in {@code Optional}.
 */
public sealed interface LookupField permits
    QueryField.QueryLookupTableField,
    ChildField.LookupTableField,
    ChildField.SplitLookupTableField,
    ChildField.RecordLookupTableField {

    LookupMapping lookupMapping();
}
