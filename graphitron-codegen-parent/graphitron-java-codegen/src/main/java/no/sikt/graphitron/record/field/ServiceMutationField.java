package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A mutation field with {@code @service} — write logic too complex for Graphitron to generate directly. */
public record ServiceMutationField(GraphQLFieldDefinition definition) implements MutationField {}
