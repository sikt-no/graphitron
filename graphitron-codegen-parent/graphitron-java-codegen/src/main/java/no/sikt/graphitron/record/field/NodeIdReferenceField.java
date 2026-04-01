package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @nodeId(typeName: ...)} — joins to the target type's table and encodes a Relay ID for
 * that row. Parallel to {@link ColumnReferenceField}. Table-mapped source context only.
 */
public record NodeIdReferenceField(GraphQLFieldDefinition definition) implements ChildField {}
