package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.Optional;

/**
 * A field bound to a column on a table joined from the source table.
 *
 * <p>{@code columnName} is present when {@code @field(name:)} overrides the default column
 * name (the GraphQL field name uppercased). {@code javaName} is present when
 * {@code @field(javaName:)} overrides the generated Java record accessor name.
 */
public record ColumnReferenceField(
    String name,
    SourceLocation location,
    Optional<String> columnName,
    Optional<String> javaName
) implements ChildField {}
