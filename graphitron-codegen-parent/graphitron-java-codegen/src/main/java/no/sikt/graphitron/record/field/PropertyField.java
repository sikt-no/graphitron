package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A scalar or nested property read from a result-mapped source. No SQL generated.
 *
 * <p>{@code columnName} is the property name used when accessing the source record:
 * the value of {@code @field(name:)} when present, otherwise the GraphQL field name.
 */
public record PropertyField(
    String name,
    SourceLocation location,
    String columnName
) implements ChildField {}
