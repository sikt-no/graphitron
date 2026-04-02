package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is a single-table interface ({@code @table} + {@code @discriminate}).
 *
 * <p>{@code cardinality} is the cardinality of this field.
 */
public record TableInterfaceQueryField(
    String parentTypeName,
    String name,
    SourceLocation location,
    FieldCardinality cardinality
) implements QueryField {}
