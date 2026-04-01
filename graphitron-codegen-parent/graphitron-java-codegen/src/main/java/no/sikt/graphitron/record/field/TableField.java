package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A child field whose return type is annotated with {@code @table}.
 *
 * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
 * used to override FK auto-inference. Empty when no {@code @reference} directive is present —
 * Graphitron will attempt to infer the foreign key automatically.
 */
public record TableField(
    String name,
    SourceLocation location,
    List<ReferencePathElement> referencePath
) implements ChildField {}
