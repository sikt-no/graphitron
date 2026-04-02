package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is a union.
 *
 * <p>{@code cardinality} is the cardinality of this field.
 */
public record UnionField(String name, SourceLocation location, FieldCardinality cardinality) implements ChildField {}
