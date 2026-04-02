package no.sikt.graphitron.record.field;

/**
 * Outcome of resolving the {@code typeName} argument of {@code @nodeId(typeName: ...)}
 * against the classified {@link no.sikt.graphitron.record.GraphitronSchema}.
 *
 * <p>{@link ResolvedNodeType} — the named type exists in the schema as a
 * {@link no.sikt.graphitron.record.type.TableType} with a
 * {@link no.sikt.graphitron.record.type.NodeDirective}. The type name is on the parent
 * {@link NodeIdReferenceField} and is not repeated here.
 *
 * <p>{@link UnresolvedNodeType} — the named type does not exist, is not a
 * {@code TableType}, or does not carry {@code @node}. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 */
public sealed interface NodeTypeStep permits ResolvedNodeType, UnresolvedNodeType {}
