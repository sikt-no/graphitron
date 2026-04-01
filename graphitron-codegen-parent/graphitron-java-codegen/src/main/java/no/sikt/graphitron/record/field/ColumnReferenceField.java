package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A field bound to a column on a joined target table. Table-mapped source context only. */
public record ColumnReferenceField(GraphQLFieldDefinition definition) implements ChildField {}
