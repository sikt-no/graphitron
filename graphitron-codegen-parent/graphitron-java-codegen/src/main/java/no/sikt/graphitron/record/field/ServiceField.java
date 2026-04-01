package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

import java.util.List;

/**
 * A child field delegating to a developer-provided service class via {@code @service}.
 *
 * <p>{@code referencePath} is the ordered list of join steps extracted from {@code @reference(path:)},
 * providing the lift condition that reconnects this field's result back to the parent table.
 * Each element should carry a {@code condition} method — no FK is involved in lift conditions.
 * Empty when no {@code @reference} directive is present.
 */
public record ServiceField(
    String name,
    SourceLocation location,
    List<ReferencePathElement> referencePath
) implements ChildField {}
