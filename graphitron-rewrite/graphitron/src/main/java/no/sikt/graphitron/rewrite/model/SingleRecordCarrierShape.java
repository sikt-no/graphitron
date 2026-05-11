package no.sikt.graphitron.rewrite.model;

/**
 * The resolved shape of a single-record DML carrier — a plain SDL Object whose single
 * {@code @table}-element field is the data field that carries the DML round-trip's
 * response rows. The carrier type itself has no Java backing class; graphql-java's
 * traversal walks through the data field's {@link ChildField.SingleRecordTableField}
 * fetcher (which runs the response SELECT keyed on the upstream DML's PK-only Result),
 * and from there through {@link GraphitronType.TableBackedType}'s per-field fetchers.
 *
 * <p>{@code dataTable} is forced to a resolved {@link TableRef} by the trigger function's
 * condition #3 ("the data field's element type is registered as
 * {@link GraphitronType.TableBackedType}"); the load-bearing
 * check {@code mutation-dml-record-field.data-table-equals-input-table} uses it as the
 * reference for the mutation classifier's table-equality admission step and for the
 * downstream consumer sites (the mutation fetcher's RETURNING PK columns and the data
 * field fetcher's response SELECT predicate).
 *
 * <p>{@code dataWrapper} is the data field's outer wrapper as authored in the SDL (single
 * or list); the data field's {@link SourceKey} cardinality is derived from the matching
 * input cardinality.
 */
public record SingleRecordCarrierShape(
    String carrierTypeName,
    String dataFieldName,
    String dataElementName,
    TableRef dataTable,
    FieldWrapper dataWrapper
) {}
