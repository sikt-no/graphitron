package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * A union type whose member types all have {@code @table}.
 */
public record UnionType(String name, SourceLocation location) implements GraphitronType {}
