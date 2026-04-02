package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A field bound to a column on a table joined from the source table.
 *
 * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
 * the directive is present, otherwise the GraphQL field name.
 *
 * <p>{@code column} is the outcome of resolving {@code columnName} against the jOOQ table:
 * {@link ResolvedColumn} when the column was found, {@link UnresolvedColumn} when it was not.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedColumn}.
 *
 * <p>{@code referencePath} is the ordered list of join steps from the source table to the target
 * column's table, extracted from {@code @reference(path:)}. Required — an empty list is a
 * validation error.
 *
 * <p>{@code javaNamePresent} is {@code true} when the {@code @field(javaName:)} argument was
 * supplied. This argument is not supported in record-based output and the validator reports an
 * error when it is present.
 */
public record ColumnReferenceField(
    String name,
    SourceLocation location,
    String columnName,
    ColumnStep column,
    List<ReferencePathElement> referencePath,
    boolean javaNamePresent
) implements ChildField {}
