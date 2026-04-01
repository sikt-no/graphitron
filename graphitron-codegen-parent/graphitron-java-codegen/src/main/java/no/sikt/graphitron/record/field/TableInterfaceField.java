package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A field targeting a single-table interface type. Graphitron projects through it. */
public record TableInterfaceField(GraphQLFieldDefinition definition) implements ChildField {}
