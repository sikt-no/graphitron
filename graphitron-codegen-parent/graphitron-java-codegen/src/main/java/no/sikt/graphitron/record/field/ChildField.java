package no.sikt.graphitron.record.field;

/**
 * A field on a non-root output type. Source context (table-mapped or result-mapped) is
 * determined by the parent {@link no.sikt.graphitron.record.type.GraphitronType} at generation time.
 */
public sealed interface ChildField extends GraphitronField
    permits ColumnField, ColumnReferenceField,
            NodeIdField, NodeIdReferenceField,
            TableField, TableMethodField,
            TableInterfaceField, InterfaceField, UnionField,
            NestingField, ConstructorField,
            ServiceField, ComputedField, PropertyField {}
