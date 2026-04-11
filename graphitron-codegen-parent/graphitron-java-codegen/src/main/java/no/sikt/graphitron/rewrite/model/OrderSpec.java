package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Normalised sort specification. Covers all three variants of {@code @order}, {@code @defaultOrder},
 * and the deprecated {@code @index} directive. Exactly one variant is present per instance.
 *
 * <p>Both {@link IndexOrder} and {@link PrimaryKeyOrder} are lookup-based: they reference a database
 * object (a named index or the table's primary key) that must be resolved against the jOOQ catalog.
 * When resolved they can be normalised to a {@link FieldsOrder}; when the lookup fails the
 * containing field is classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} at build time.
 *
 * <ul>
 *   <li>{@link IndexOrder} — sort by a named database index (from {@code @order(index:)},
 *       {@code @defaultOrder(index:)}, or deprecated {@code @index(name:)}); stored when the
 *       index is found in the jOOQ catalog
 *   <li>{@link FieldsOrder} — sort by an explicit list of columns (from {@code @order(fields:)}
 *       or {@code @defaultOrder(fields:)}); always fully resolved
 *   <li>{@link PrimaryKeyOrder} — sort by the table's primary key (from
 *       {@code @order(primaryKey: true)} or {@code @defaultOrder(primaryKey: true)}); stored when
 *       the primary key is found
 * </ul>
 */
public sealed interface OrderSpec
    permits OrderSpec.IndexOrder, OrderSpec.FieldsOrder, OrderSpec.PrimaryKeyOrder {

    /** Sort by a named database index. */
    record IndexOrder(String indexName) implements OrderSpec {}

    /** Sort by an explicit list of columns, each with an optional collation. */
    record FieldsOrder(List<SortFieldSpec> fields) implements OrderSpec {}

    /** Sort by the table's primary key. */
    record PrimaryKeyOrder() implements OrderSpec {}
}
