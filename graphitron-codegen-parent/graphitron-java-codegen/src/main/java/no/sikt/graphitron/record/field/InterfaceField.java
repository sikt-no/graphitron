package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A field targeting a multi-table interface type. Graphitron projects through it. */
public record InterfaceField(GraphQLFieldDefinition definition) implements ChildField {}
