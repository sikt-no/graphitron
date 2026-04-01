package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A root query field targeting a union type whose member types all have {@code @table}. */
public record UnionQueryField(GraphQLFieldDefinition definition) implements QueryField {}
