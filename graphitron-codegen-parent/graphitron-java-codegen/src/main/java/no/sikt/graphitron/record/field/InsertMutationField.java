package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A mutation field with {@code @mutation(typeName: INSERT)}. */
public record InsertMutationField(GraphQLFieldDefinition definition) implements MutationField {}
