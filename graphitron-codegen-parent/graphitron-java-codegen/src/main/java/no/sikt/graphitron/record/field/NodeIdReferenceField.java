package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * An {@code @nodeId(typeName: ...)} field that joins to a target type's table and encodes a Relay Global ID.
 *
 * <p>{@code typeName} is the value of the {@code typeName} argument on the {@code @nodeId} directive
 * (e.g. {@code "Film"}). It identifies which type's {@code @node} key columns are encoded in the ID.
 *
 * <p>{@code nodeType} is the outcome of resolving {@code typeName} against the classified schema:
 * {@link ResolvedNodeType} when the named type exists and carries {@code @node},
 * {@link UnresolvedNodeType} when it does not. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedNodeType}.
 *
 * <p>{@code referencePath} is the ordered list of join steps from the source table to the target
 * type's table, extracted from {@code @reference(path:)}. May be empty when there is exactly one
 * foreign key between the source and target tables (implicit join). The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error when the path is
 * empty and there is no foreign key or more than one foreign key between the tables. When both a
 * path and a {@code typeName} are supplied the path must lead to the target type's table.
 */
public record NodeIdReferenceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    String typeName,
    NodeTypeStep nodeType,
    List<ReferencePathElement> referencePath
) implements ChildField {}
