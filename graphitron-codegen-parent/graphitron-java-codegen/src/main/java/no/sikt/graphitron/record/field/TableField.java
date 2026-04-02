package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;


/**
 * A child field whose return type is annotated with {@code @table}.
 *
 * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
 * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
 * Graphitron will attempt to infer the foreign key automatically.
 *
 * <p>{@code condition} is the resolved or unresolved field-level {@code @condition} directive, or
 * {@link FieldConditionStep.NoFieldCondition} when no {@code @condition} is present. The validator
 * reports an error for an {@link FieldConditionStep.UnresolvedFieldCondition}.
 *
 * <p>{@code defaultOrder} carries the parsed {@code @defaultOrder} directive, or {@code null}
 * when the directive is absent. The validator reports an error if the spec contains an unresolved
 * index or primary key (see {@link OrderSpec.UnresolvedIndexOrder} and
 * {@link OrderSpec.UnresolvedPrimaryKeyOrder}).
 */
public record TableField(
    String name,
    SourceLocation location,
    List<ReferencePathElement> referencePath,
    FieldConditionStep condition,
    DefaultOrderSpec defaultOrder
) implements ChildField {}
