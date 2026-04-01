package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A field bound to a column on the source table. Table-mapped source context only. */
public record ColumnField(GraphQLFieldDefinition definition) implements ChildField {}
