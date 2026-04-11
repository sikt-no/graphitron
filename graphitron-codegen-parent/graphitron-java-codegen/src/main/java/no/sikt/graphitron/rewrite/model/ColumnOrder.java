package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * A fully-resolved sort specification: an ordered list of columns (each with an optional
 * collation) and a sort direction.
 *
 * <p>Normalises the three {@code @defaultOrder}/{@code @order} source variants at build time:
 * <ul>
 *   <li>{@code index:} — columns are the fields of the named database index, resolved via the
 *       jOOQ catalog; collation is always {@code null}</li>
 *   <li>{@code primaryKey:} — columns are the primary-key fields of the return type's table,
 *       resolved via the jOOQ catalog; collation is always {@code null}</li>
 *   <li>{@code fields:} — columns are resolved against the return type's table by SQL name;
 *       each entry carries the optional {@code collate:} value</li>
 * </ul>
 * When any catalog lookup fails the containing field is classified as
 * {@link GraphitronField.UnclassifiedField} at build time rather than emitting an unresolved spec.
 *
 * <p>{@code direction} is {@code "ASC"} or {@code "DESC"} (directive default is {@code "ASC"}).
 */
public record ColumnOrder(List<ColumnOrderEntry> columns, String direction) {

    /**
     * One column in a {@link ColumnOrder}: a resolved {@link ColumnRef} paired with an optional
     * collation string.
     *
     * <p>{@code collation} is the {@code collate:} value from an {@code @order} or
     * {@code @defaultOrder} {@code fields:} entry (e.g. {@code "C"}), or {@code null} when not
     * specified. Index-based and primary-key-based orders never carry a collation.
     */
    public record ColumnOrderEntry(ColumnRef column, String collation) {}
}
