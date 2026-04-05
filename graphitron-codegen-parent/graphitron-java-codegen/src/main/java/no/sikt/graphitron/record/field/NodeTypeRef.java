package no.sikt.graphitron.record.field;

import no.sikt.graphitron.record.type.NodeRef.NodeDirective;

/**
 * Outcome of resolving the {@code typeName} argument of {@code @nodeId(typeName: ...)} against
 * the classified {@link no.sikt.graphitron.record.GraphitronSchema} and verifying that the named
 * type carries a {@code @node} directive.
 *
 * <p>{@link ResolvedNodeType} — the named type exists in the schema as a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} and carries {@code @node}.
 * The {@link NodeDirective} holds the {@code @node} directive properties ({@code typeId} and
 * {@code keyColumns}) used at code-generation time for Relay Global ID encoding.
 *
 * <p>{@link UnresolvedNodeType} — the named type does not exist, is not a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType}, or does not carry {@code @node}.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 *
 * <p>Table resolution for FK validation and path checking is carried separately on the containing
 * {@link ChildField.NodeIdReferenceField} via {@code targetType} and {@code parentTable}.
 */
public sealed interface NodeTypeRef permits NodeTypeRef.ResolvedNodeType, NodeTypeRef.UnresolvedNodeType {

    /**
     * The type named by {@code @nodeId(typeName:)} was found in the schema as a
     * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a {@code @node}
     * directive. {@code node} carries the directive properties used for ID encoding.
     */
    record ResolvedNodeType(NodeDirective node) implements NodeTypeRef {}

    /**
     * The type named by {@code @nodeId(typeName:)} could not be resolved: the type does not
     * exist in the schema, is not a {@link no.sikt.graphitron.record.type.GraphitronType.TableType},
     * or does not carry {@code @node}.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}.
     * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
     */
    record UnresolvedNodeType() implements NodeTypeRef {}
}
