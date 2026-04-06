package no.sikt.graphitron.rewrite.field;

import java.util.List;

/**
 * Normalised sort specification. Covers all three variants of {@code @order}, {@code @defaultOrder},
 * and the deprecated {@code @index} directive. Exactly one variant is present per instance.
 *
 * <p>Both {@link IndexOrder} and {@link PrimaryKeyOrder} are lookup-based: they reference a database
 * object (a named index or the table's primary key) that must be resolved against the jOOQ catalog.
 * When resolved they can be normalised to a {@link FieldsOrder}; when the lookup fails the
 * corresponding error variant is used instead.
 *
 * <ul>
 *   <li>{@link IndexOrder} — sort by a named database index (from {@code @order(index:)},
 *       {@code @defaultOrder(index:)}, or deprecated {@code @index(name:)}); resolves to
 *       {@link FieldsOrder} when the index is found, or {@link UnresolvedIndexOrder} when not
 *   <li>{@link FieldsOrder} — sort by an explicit list of columns (from {@code @order(fields:)}
 *       or {@code @defaultOrder(fields:)}); always fully resolved
 *   <li>{@link PrimaryKeyOrder} — sort by the table's primary key (from
 *       {@code @order(primaryKey: true)} or {@code @defaultOrder(primaryKey: true)}); resolves to
 *       {@link FieldsOrder} when the key is found, or {@link UnresolvedPrimaryKeyOrder} when not
 *   <li>{@link UnresolvedIndexOrder} — the named index could not be found in the jOOQ catalog;
 *       the {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error
 *   <li>{@link UnresolvedPrimaryKeyOrder} — the table's primary key could not be found (the table
 *       may not have one); the validator reports an error
 * </ul>
 */
public sealed interface OrderSpec
    permits OrderSpec.IndexOrder, OrderSpec.FieldsOrder, OrderSpec.PrimaryKeyOrder,
            OrderSpec.UnresolvedIndexOrder, OrderSpec.UnresolvedPrimaryKeyOrder {

    /** Sort by a named database index. Normalises to {@link FieldsOrder} when the index is resolved. */
    record IndexOrder(String indexName) implements OrderSpec {}

    /** Sort by an explicit list of columns, each with an optional collation. */
    record FieldsOrder(List<SortFieldSpec> fields) implements OrderSpec {}

    /** Sort by the table's primary key. Normalises to {@link FieldsOrder} when the key is resolved. */
    record PrimaryKeyOrder() implements OrderSpec {}

    /** The named index could not be found in the jOOQ catalog. {@code indexName} is the raw value from the directive. */
    record UnresolvedIndexOrder(String indexName) implements OrderSpec {}

    /** The table's primary key could not be found — the table may not have one. */
    record UnresolvedPrimaryKeyOrder() implements OrderSpec {}
}
