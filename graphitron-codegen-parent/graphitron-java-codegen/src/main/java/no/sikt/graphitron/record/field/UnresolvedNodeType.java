package no.sikt.graphitron.record.field;

/**
 * A {@link NodeTypeStep} indicating that the type named by {@code @nodeId(typeName:)} could
 * not be resolved: the type does not exist in the schema, is not a
 * {@link no.sikt.graphitron.record.type.TableType}, or does not carry {@code @node}.
 *
 * <p>The type name is stored on the parent {@link NodeIdReferenceField}; it is not repeated here.
 * The {@link no.sikt.graphitron.record.GraphitronSchemaValidator} reports an error for this step.
 */
public record UnresolvedNodeType() implements NodeTypeStep {}
