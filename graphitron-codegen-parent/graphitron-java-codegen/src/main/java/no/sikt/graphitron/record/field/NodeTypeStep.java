package no.sikt.graphitron.record.field;

/**
 * Outcome of resolving the {@code typeName} argument of {@code @nodeId(typeName: ...)}
 * against the classified {@link no.sikt.graphitron.record.GraphitronSchema}.
 *
 * <p>{@link ResolvedNodeType} — the named type exists in the schema as a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a
 * {@link no.sikt.graphitron.record.type.NodeStep.NodeDirective}. The type name is on the parent
 * {@link ChildField.NodeIdReferenceField} and is not repeated here.
 *
 * <p>{@link UnresolvedNodeType} — the named type does not exist, is not a
 * {@code TableType}, or does not carry {@code @node}. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 */
public sealed interface NodeTypeStep permits NodeTypeStep.ResolvedNodeType, NodeTypeStep.UnresolvedNodeType {

    /**
     * A {@link NodeTypeStep} indicating that the type named by {@code @nodeId(typeName:)} was
     * found in the schema as a {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a
     * {@link no.sikt.graphitron.record.type.NodeStep.NodeDirective}.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}; it is not repeated here.
     */
    record ResolvedNodeType() implements NodeTypeStep {}

    /**
     * A {@link NodeTypeStep} indicating that the type named by {@code @nodeId(typeName:)} could
     * not be resolved: the type does not exist in the schema, is not a
     * {@link no.sikt.graphitron.record.type.GraphitronType.TableType}, or does not carry {@code @node}.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}; it is not repeated here.
     * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for this step.
     */
    record UnresolvedNodeType() implements NodeTypeStep {}
}
