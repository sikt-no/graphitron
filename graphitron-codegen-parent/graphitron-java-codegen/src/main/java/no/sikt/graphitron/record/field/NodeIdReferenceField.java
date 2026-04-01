package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * An {@code @nodeId(typeName: ...)} field that joins to a target type's table and encodes a Relay Global ID.
 */
public record NodeIdReferenceField(String name, SourceLocation location) implements ChildField {}
