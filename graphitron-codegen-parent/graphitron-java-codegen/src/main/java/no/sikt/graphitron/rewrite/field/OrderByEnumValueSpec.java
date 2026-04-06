package no.sikt.graphitron.rewrite.field;

/**
 * The sort specification for a single value in an {@code @orderBy} input enum.
 *
 * <p>Each enum value is annotated with {@code @order} (or the deprecated {@code @index}), which
 * this record normalises into a single {@link OrderSpec}. The {@code name} is the GraphQL enum
 * value name (e.g. {@code "TITLE"}).
 */
public record OrderByEnumValueSpec(String name, OrderSpec spec) {}
