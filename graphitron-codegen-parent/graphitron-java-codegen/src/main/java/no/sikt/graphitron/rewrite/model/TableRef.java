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
 * <p>{@code hasPrimaryKey} is {@code true} when the jOOQ table has a declared primary key.
 *
 * <p>{@code primaryKeyColumnSqlNames} is the ordered list of SQL column names that form the
 * primary key (e.g. {@code ["language_id"]}), populated from
 * {@code table.getPrimaryKey().getFields()} at parse time. Empty when there is no primary key.
 *
 * <p>{@code primaryKeyColumnJavaTypes} is the parallel list of binary Java class names for
 * each primary-key column (e.g. {@code ["java.lang.Long"]} for a {@code BIGINT} column).
 *
 * <p>When the owning GraphQL type also carries {@code @node}, the {@code @node} directive
 * properties ({@code typeId} and key columns) are carried by a separate
 * {@link NodeRef} stored alongside this {@code TableRef} in
 * {@link GraphitronType.TableType}.
 */
public record TableRef(
    String tableName,
    String javaFieldName,
    String javaClassName,
    boolean hasPrimaryKey,
    List<String> primaryKeyColumnSqlNames,
    List<String> primaryKeyColumnJavaTypes
) {}
