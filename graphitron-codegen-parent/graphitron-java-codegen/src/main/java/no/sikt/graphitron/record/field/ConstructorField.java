package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * Planned — a directive carries the field-to-constructor-parameter mapping. Graphitron does
 * not project through it. Table-mapped source context only.
 */
public record ConstructorField(GraphQLFieldDefinition definition) implements ChildField {}
