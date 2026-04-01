package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLObjectType;

/**
 * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
 * a scope is entered via a child field.
 */
public record RootType(GraphQLObjectType definition) implements GraphitronType {}
