package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is a union.
 */
public record UnionQueryField(String name, SourceLocation location) implements QueryField {}
