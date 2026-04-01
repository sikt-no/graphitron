package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A general root query field targeting a table-mapped type. */
public record TableQueryField(GraphQLFieldDefinition definition) implements QueryField {}
