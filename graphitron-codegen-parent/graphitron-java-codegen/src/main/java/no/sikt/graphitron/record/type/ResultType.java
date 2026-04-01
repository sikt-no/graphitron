package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLObjectType;

/**
 * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
 */
public record ResultType(GraphQLObjectType definition) implements GraphitronType {}
