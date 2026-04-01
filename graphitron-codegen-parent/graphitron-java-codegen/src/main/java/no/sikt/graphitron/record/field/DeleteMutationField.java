package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A mutation field with {@code @mutation(typeName: DELETE)}. Deleted rows cannot be queried back —
 * the return type is a success flag, count, or ordered input echo. No lift applies.
 */
public record DeleteMutationField(GraphQLFieldDefinition definition) implements MutationField {}
