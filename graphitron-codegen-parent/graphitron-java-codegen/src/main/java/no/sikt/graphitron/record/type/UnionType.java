package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLUnionType;

/**
 * A union type whose member types all have {@code @table}.
 */
public record UnionType(GraphQLUnionType definition) implements GraphitronType {}
