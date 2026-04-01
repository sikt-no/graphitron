package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @service} on a child field — executes in a private scope. From table-mapped source,
 * Graphitron controls what is passed to the service. From result-mapped source, input is locked
 * to what the record carries. LiftCondition applies if the return type is table-mapped.
 */
public record ServiceField(GraphQLFieldDefinition definition) implements ChildField {}
