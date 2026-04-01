package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * {@code @nodeId} — encodes a globally unique Relay ID from the source type's key columns
 * ({@code @node(keyColumns:...)}). The source type must have {@code @node}.
 * Table-mapped source context only.
 */
public record NodeIdField(GraphQLFieldDefinition definition) implements ChildField {}
