package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A field targeting a union type. Graphitron projects through it. */
public record UnionField(GraphQLFieldDefinition definition) implements ChildField {}
