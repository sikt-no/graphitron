package no.sikt.graphitron.record.field;

import no.sikt.graphitron.record.type.TableRef.ResolvedTable;

/**
 * Outcome of resolving the {@code typeName} argument of {@code @nodeId(typeName: ...)}
 * against the classified {@link no.sikt.graphitron.record.GraphitronSchema}.
 *
 * <p>{@link ResolvedNodeType} — the named type exists in the schema as a
 * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a
 * {@link no.sikt.graphitron.record.type.NodeRef.NodeDirective}. The resolved tables of
 * both the target type and the parent type are carried here for FK validation.
 *
 * <p>{@link UnresolvedNodeType} — the named type does not exist, is not a
 * {@code TableType}, or does not carry {@code @node}. The
 * {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error.
 */
public sealed interface NodeTypeRef permits NodeTypeRef.ResolvedNodeType, NodeTypeRef.UnresolvedNodeType {

    /**
     * The type named by {@code @nodeId(typeName:)} was found in the schema as a
     * {@link no.sikt.graphitron.record.type.GraphitronType.TableType} with a
     * {@link no.sikt.graphitron.record.type.NodeRef.NodeDirective}.
     *
     * <p>{@code targetTable} is the {@link ResolvedTable} of the named type, or {@code null}
     * when its table is unresolved. A null target table skips FK and path validation; the
     * unresolved table is reported by the type validator instead.
     *
     * <p>{@code parentTable} is the {@link ResolvedTable} of the containing type, or {@code null}
     * when the parent's table is unresolved. A null parent table skips the implicit FK count check.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}.
     */
    record ResolvedNodeType(ResolvedTable targetTable, ResolvedTable parentTable) implements NodeTypeRef {}

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
