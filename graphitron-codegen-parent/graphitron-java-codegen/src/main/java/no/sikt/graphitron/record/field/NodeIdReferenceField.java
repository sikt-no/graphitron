package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * An {@code @nodeId(typeName: ...)} field that joins to a target type's table and encodes a Relay Global ID.
 *
 * <p>{@code referencePath} is the ordered list of join steps from the source table to the target
 * type's table, extracted from {@code @reference(path:)}. Required — an empty list is a
 * validation error.
 */
public record NodeIdReferenceField(
    String name,
    SourceLocation location,
    List<ReferencePathElement> referencePath
) implements ChildField {}
