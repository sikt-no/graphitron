package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field resolved by a developer-provided jOOQ {@code Field<?>} expression via {@code @computed}.
 */
public record ComputedField(String name, SourceLocation location) implements ChildField {}
