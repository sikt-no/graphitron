package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A mutation field with {@code @mutation(typeName: UPSERT)}. */
public record UpsertMutationField(GraphQLFieldDefinition definition) implements MutationField {}
