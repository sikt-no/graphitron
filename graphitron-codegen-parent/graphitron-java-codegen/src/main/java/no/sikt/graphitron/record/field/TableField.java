package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A field with a table-mapped target type. Graphitron handles projection, ordering, pagination,
 * and nested scopes. In result-mapped source context, starts a new scope via DataLoader +
 * LiftCondition. {@code @splitQuery} forces a new scope even in table-mapped source context.
 */
public record TableField(GraphQLFieldDefinition definition) implements ChildField {}
