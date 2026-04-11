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
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} at build time
 * rather than emitting an unresolved spec.
 *
 * <p>{@code direction} is {@code "ASC"} or {@code "DESC"} (directive default is {@code "ASC"}).
 */
public record ColumnOrder(List<ColumnOrderEntry> columns, String direction) {}
