package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** {@code Query.node(id:)} — Relay Global Object Identification. Table-mapped via global ID. */
public record NodeQueryField(GraphQLFieldDefinition definition) implements QueryField {}
