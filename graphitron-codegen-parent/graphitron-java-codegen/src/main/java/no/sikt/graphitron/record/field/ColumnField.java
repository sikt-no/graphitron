package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A scalar or enum field bound to a column on the source table.
 *
 * <p>{@code columnName} is the database column name: the value of {@code @field(name:)} when
 * the directive is present, otherwise the GraphQL field name.
 */
public record ColumnField(
    String name,
    SourceLocation location,
    String columnName
) implements ChildField {}
