package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A root query field with {@code @tableMethod} — the developer provides a filtered {@code Table<?>}.
 * Graphitron handles all projection, ordering, pagination, and nested scopes within the created scope.
 */
public record TableMethodQueryField(GraphQLFieldDefinition definition) implements QueryField {}
