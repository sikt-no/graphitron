package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A root query field whose return type is annotated with {@code @table}.
 *
 * <p>{@code defaultOrder} carries the parsed {@code @defaultOrder} directive, or {@code null}
 * when the directive is absent. The validator reports an error if the spec is structurally invalid
 * (e.g. more than one mode is set).
 *
 * <p>{@code orderByValues} is the list of enum value specs for the {@code @orderBy} input argument,
 * or an empty list when no {@code @orderBy} argument is present. Each element is one enum value
 * annotated with {@code @order} (or the deprecated {@code @index}), normalised into an
 * {@link OrderSpec}.
 */
public record TableQueryField(
    String name,
    SourceLocation location,
    DefaultOrderSpec defaultOrder,
    List<OrderByEnumValueSpec> orderByValues
) implements QueryField {}
