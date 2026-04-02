package no.sikt.graphitron.record.field;

/**
 * A {@link NodeTypeStep} indicating that the type named by {@code @nodeId(typeName:)} was
 * found in the schema as a {@link no.sikt.graphitron.record.type.TableType} with a
 * {@link no.sikt.graphitron.record.type.NodeDirective}.
 *
 * <p>The type name is stored on the parent {@link NodeIdReferenceField}; it is not repeated here.
 */
public record ResolvedNodeType() implements NodeTypeStep {}
