package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A mutation field with {@code @mutation(typeName: UPDATE)}. */
public record UpdateMutationField(GraphQLFieldDefinition definition) implements MutationField {}
