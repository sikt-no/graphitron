package no.sikt.graphitron.rewrite.model;

/**
 * One column in a {@link ColumnOrder}: a resolved {@link ColumnRef} paired with an optional
 * collation string.
 *
 * <p>{@code collation} is the {@code collate:} value from an {@code @order} or
 * {@code @defaultOrder} {@code fields:} entry (e.g. {@code "C"}), or {@code null} when not
 * specified. Index-based and primary-key-based orders never carry a collation.
 */
public record ColumnOrderEntry(ColumnRef column, String collation) {}
