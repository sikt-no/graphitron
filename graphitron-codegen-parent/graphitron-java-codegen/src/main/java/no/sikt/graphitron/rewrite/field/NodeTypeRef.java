package no.sikt.graphitron.rewrite.field;

import no.sikt.graphitron.rewrite.type.NodeRef.NodeDirective;

/**
 * Outcome of resolving the {@code typeName} argument of {@code @nodeId(typeName: ...)} against
 * the classified {@link no.sikt.graphitron.rewrite.GraphitronSchema} and verifying that the named
 * type carries a {@code @node} directive.
 *
 * <p>{@link ResolvedNodeType} — the named type exists in the schema as a
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} and carries {@code @node}.
 * The {@link NodeDirective} holds the {@code @node} directive properties ({@code typeId} and
 * {@code keyColumns}) used at code-generation time for Relay Global ID encoding.
 *
 * <p>{@link NoNodeDirectiveType} — the named type exists in the schema but either is not a
 * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} or does not carry {@code @node}.
 * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error.
 *
 * <p>{@link NotFoundNodeType} — the type name string does not correspond to any type in the
 * schema. Unlike field return types (which graphql-java validates at schema assembly time),
 * directive argument strings are not schema-validated, so a non-existent name is a user error
 * that Graphitron must detect. The validator reports an error distinct from the
 * {@link NoNodeDirectiveType} case so users know whether to define the type or add {@code @node}.
 *
 * <p>Table resolution for FK validation and path checking is carried separately on the containing
 * {@link ChildField.NodeIdReferenceField} via {@code targetType} and {@code parentTable}.
 */
public sealed interface NodeTypeRef
    permits NodeTypeRef.ResolvedNodeType, NodeTypeRef.NoNodeDirectiveType, NodeTypeRef.NotFoundNodeType {

    /**
     * The type named by {@code @nodeId(typeName:)} was found in the schema as a
     * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} with a {@code @node}
     * directive. {@code node} carries the directive properties used for ID encoding.
     */
    record ResolvedNodeType(NodeDirective node) implements NodeTypeRef {}

    /**
     * The type named by {@code @nodeId(typeName:)} exists in the schema but either is not a
     * {@link no.sikt.graphitron.rewrite.type.GraphitronType.TableType} or does not carry
     * {@code @node}.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error.
     */
    record NoNodeDirectiveType() implements NodeTypeRef {}

    /**
     * The type name string from {@code @nodeId(typeName:)} does not match any type in the schema.
     * Directive argument strings are not validated by graphql-java, so a non-existent name reaches
     * the builder and is caught here.
     *
     * <p>The type name is stored on the parent {@link ChildField.NodeIdReferenceField}.
     * The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error distinct
     * from {@link NoNodeDirectiveType} so users know to define the type rather than add
     * {@code @node} to an existing one.
     */
    record NotFoundNodeType() implements NodeTypeRef {}
}
