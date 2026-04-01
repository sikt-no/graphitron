package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * An {@code @nodeId} field that encodes a Relay Global ID from the source type's key columns.
 */
public record NodeIdField(String name, SourceLocation location) implements ChildField {}
