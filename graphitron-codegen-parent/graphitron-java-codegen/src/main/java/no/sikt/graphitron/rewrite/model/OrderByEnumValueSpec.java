package no.sikt.graphitron.rewrite.model;

/**
 * The sort specification for a single value in an {@code @orderBy} input enum.
 *
 * <p>Each enum value is annotated with {@code @order} (or the deprecated {@code @index}), which
 * this record normalises into a fully-resolved {@link ColumnOrder}. The {@code name} is the
 * GraphQL enum value name (e.g. {@code "TITLE"}).
 */
public record OrderByEnumValueSpec(String name, ColumnOrder order) {}
