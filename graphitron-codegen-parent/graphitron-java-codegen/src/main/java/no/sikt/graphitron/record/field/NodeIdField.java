package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;
import no.sikt.graphitron.record.type.NodeStep;

/**
 * An {@code @nodeId} field that encodes a Relay Global ID from the source type's key columns.
 *
 * <p>{@code parentTypeName} is the name of the containing GraphQL type.
 *
 * <p>{@code node} is the parent type's {@code @node} step: a
 * {@link no.sikt.graphitron.record.type.NodeDirective} carrying the optional {@code typeId} and
 * the list of key columns when {@code @node} is present, or
 * {@link no.sikt.graphitron.record.type.NoNode} when it is absent (a validation error).
 */
public record NodeIdField(
    String parentTypeName,
    String name,
    SourceLocation location,
    NodeStep node
) implements ChildField {}
