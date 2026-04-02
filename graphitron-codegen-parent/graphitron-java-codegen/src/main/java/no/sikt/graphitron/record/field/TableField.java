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
 * <p>{@code cardinality} is the cardinality of this field — {@link FieldCardinality.Single} for a
 * 1:1 join, {@link FieldCardinality.List} for a 1:N join, or {@link FieldCardinality.Connection}
 * for a Relay paginated list. The validator reports errors for unresolved ordering specs on list
 * and connection variants.
 */
public record TableField(
    String parentTypeName,
    String name,
    SourceLocation location,
    List<ReferencePathElement> referencePath,
    FieldConditionStep condition,
    FieldCardinality cardinality
) implements ChildField {}
