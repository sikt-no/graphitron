package no.sikt.graphitron.rewrite.model;

/**
 * The resolved shape of a single-record carrier — a plain SDL Object whose single data field
 * is the field that carries the producing operation's response value. R75 Phase 1: DML
 * mutation carriers with {@code @table}-element data ({@link DataElement.Table}); R75 Phase 2:
 * {@code @service} mutation carriers with {@code @table}- or record-element data
 * ({@link DataElement.Table} or {@link DataElement.Record}).
 *
 * <p>{@code dataElement} discriminates the element kind. {@link DataElement.Table} carries
 * the resolved {@link TableRef} that the load-bearing classifier check
 * {@code mutation-dml-record-field.data-table-equals-input-table} compares against the DML's
 * input table, and that the data-field fetcher uses to build its follow-up SELECT predicate.
 * {@link DataElement.Record} carries the backing class name that the {@code @service} mutation
 * classifier matches against the method's reflected return type. The wrapper (single or list)
 * lives on the {@link DataElement} arms; the data field's {@link SourceKey} cardinality (Phase 1
 * {@link ChildField.SingleRecordTableField} only) is derived from the matching input cardinality.
 */
public record SingleRecordCarrierShape(
    String carrierTypeName,
    String dataFieldName,
    DataElement dataElement
) {}
