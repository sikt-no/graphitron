package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field using {@code @tableMethod} — the developer provides a pre-filtered {@code Table<?>}.
 *
 * <p>{@code cardinality} is the cardinality of this field.
 */
public record TableMethodQueryField(String name, SourceLocation location, FieldCardinality cardinality) implements QueryField {}
