package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is annotated with {@code @table}.
 *
 * <p>{@code cardinality} is the cardinality of this field — {@link FieldCardinality.Single} for a
 * single-item lookup, {@link FieldCardinality.List} for a list result, or
 * {@link FieldCardinality.Connection} for a Relay paginated list. For list and connection variants,
 * {@code defaultOrder} and {@code orderByValues} are carried inside the cardinality record. The
 * validator reports errors for unresolved ordering specs.
 */
public record TableQueryField(
    String name,
    SourceLocation location,
    FieldCardinality cardinality
) implements QueryField {}
