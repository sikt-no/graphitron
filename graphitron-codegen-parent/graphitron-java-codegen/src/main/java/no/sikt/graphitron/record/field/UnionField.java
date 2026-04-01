package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is a union.
 */
public record UnionField(String name, SourceLocation location) implements ChildField {}
