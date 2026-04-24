package no.sikt.graphitron.rewrite.model;

/**
 * Capability interface for the four field variants that are backed by {@code @lookupKey}
 * arguments and generate a VALUES + JOIN lookup query: {@link QueryField.QueryLookupTableField}
 * (root synchronous lookup), {@link ChildField.LookupTableField} (table-mapped parent, inline
 * correlated subquery), {@link ChildField.SplitLookupTableField} (table-mapped parent,
 * DataLoader-backed), and {@link ChildField.RecordLookupTableField} (result-mapped parent,
 * DataLoader-backed).
 *
 * <p>Per the {@code capability interfaces over dispatch chains} principle in
 * {@code docs/rewrite-roadmap.md}, the generator routes on this capability rather than on a
 * per-variant {@code instanceof} chain. See {@code docs/argument-resolution.md} for the
 * design.
 *
 * <p>{@link #lookupMapping()} is always non-null for these variants — lookups are defined by the
 * presence of {@code @lookupKey} args, which always resolve to a {@link LookupMapping.ColumnMapping}
 * with at least one column, or to a {@link LookupMapping.NodeIdMapping} for node-ID args.
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
