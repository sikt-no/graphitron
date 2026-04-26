package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A jOOQ table resolved from a {@code @table} directive value.
 *
 * <p>A {@code TableRef} is only constructed when the table name matches an entry in the jOOQ
 * catalog. When the name cannot be matched the containing type is classified as
 * {@link GraphitronType.UnclassifiedType} at build time.
 *
 * <p>{@code tableName} is the raw SQL table name from the {@code @table} directive
 * (e.g. {@code "film"}).
 *
 * <p>{@code javaFieldName} is the field name in the generated jOOQ {@code Tables} class
 * (e.g. {@code "FILM"}). {@code javaClassName} is the simple class name of the generated jOOQ
 * table class (e.g. {@code "Film"}), taken directly from the live class via reflection.
 *
 * <p>{@code primaryKeyColumns} is empty when the table has no primary key, or contains the
 * ordered list of PK columns (each a fully resolved {@link ColumnRef}) populated from
 * {@code table.getPrimaryKey().getFields()} at parse time.
 *
 * <p>When the owning GraphQL type also carries {@code @node}, the type is classified as
 * {@link GraphitronType.NodeType} instead of {@link GraphitronType.TableType}, with the
 * {@code @node} directive properties ({@code typeId} and key columns) stored directly on it.
 */
public record TableRef(
    String tableName,
    String javaFieldName,
    String javaClassName,
    List<ColumnRef> primaryKeyColumns
) {
    public boolean hasPrimaryKey() {
        return !primaryKeyColumns.isEmpty();
    }
}
