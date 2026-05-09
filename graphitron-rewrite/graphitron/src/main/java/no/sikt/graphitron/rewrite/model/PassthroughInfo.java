package no.sikt.graphitron.rewrite.model;

/**
 * The resolved shape of a passthrough payload — a wire-format wrapper SDL Object whose
 * single {@code @table}-element field is the actual carrier of the DML round-trip's
 * {@code Result<Record>}. The payload type itself has no Java backing class; graphql-java's
 * own list iteration through the existing {@code @table} per-field fetchers does the
 * traversal.
 *
 * <p>{@code dataTable} is forced to a resolved {@link TableRef} by the trigger function's
 * condition #3 ("the data field's element type is registered as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.TableBackedType}"); the load-bearing
 * check {@code passthrough-payload.data-table-equals-dml-target} uses it as the
 * load-bearing reference for the DML emitter's {@code RETURNING $fields(table)}
 * projection.
 *
 * <p>{@code dataWrapper} is the data field's outer wrapper as authored in the SDL
 * (single or list); the existing
 * {@link no.sikt.graphitron.rewrite.model.DmlReturnExpression}{@code .{ProjectedSingle,
 * ProjectedList}} dispatch handles both cardinalities downstream.
 */
public record PassthroughInfo(
    String payloadTypeName,
    String dataFieldName,
    String dataElementName,
    TableRef dataTable,
    FieldWrapper dataWrapper
) {}
