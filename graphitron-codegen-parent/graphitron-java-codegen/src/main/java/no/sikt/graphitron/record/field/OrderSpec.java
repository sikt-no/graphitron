package no.sikt.graphitron.record.field;

import java.util.List;

/**
 * Normalised sort specification. Covers all three variants of {@code @order}, {@code @defaultOrder},
 * and the deprecated {@code @index} directive. Exactly one variant is present per instance.
 *
 * <ul>
 *   <li>{@link IndexOrder} — sort by a named database index (from {@code @order(index:)},
 *       {@code @defaultOrder(index:)}, or deprecated {@code @index(name:)})
 *   <li>{@link FieldsOrder} — sort by an explicit list of columns (from {@code @order(fields:)}
 *       or {@code @defaultOrder(fields:)})
 *   <li>{@link PrimaryKeyOrder} — sort by the table's primary key (from
 *       {@code @order(primaryKey: true)} or {@code @defaultOrder(primaryKey: true)})
 * </ul>
 */
public sealed interface OrderSpec permits OrderSpec.IndexOrder, OrderSpec.FieldsOrder, OrderSpec.PrimaryKeyOrder {

    /** Sort by a named database index. */
    record IndexOrder(String indexName) implements OrderSpec {}

    /** Sort by an explicit list of columns, each with an optional collation. */
    record FieldsOrder(List<SortFieldSpec> fields) implements OrderSpec {}

    /** Sort by the table's primary key. */
    record PrimaryKeyOrder() implements OrderSpec {}
}
