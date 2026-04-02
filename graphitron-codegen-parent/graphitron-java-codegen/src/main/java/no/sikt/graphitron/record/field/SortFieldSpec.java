package no.sikt.graphitron.record.field;

/**
 * A single field in an explicit sort specification, parsed from {@code @order(fields:)} or
 * {@code @defaultOrder(fields:)}.
 *
 * <p>{@code columnName} is the database column name. {@code collation} is the optional collation
 * string (e.g. {@code "C"}), or {@code null} when not specified.
 */
public record SortFieldSpec(String columnName, String collation) {}
