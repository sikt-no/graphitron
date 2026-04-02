package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A scalar or enum field bound to a column on the source table.
 *
 * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
 * the directive is present, otherwise the GraphQL field name.
 *
 * <p>{@code column} is the outcome of resolving {@code columnName} against the jOOQ table:
 * {@link ResolvedColumn} when the column was found, {@link UnresolvedColumn} when it was not.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedColumn}.
 *
 * <p>{@code javaNamePresent} is {@code true} when the {@code @field(javaName:)} argument was
 * supplied. This argument is not supported in record-based output and the validator reports an
 * error when it is present.
 */
public record ColumnField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String columnName,
    ColumnStep column,
    boolean javaNamePresent
) implements ChildField {}
