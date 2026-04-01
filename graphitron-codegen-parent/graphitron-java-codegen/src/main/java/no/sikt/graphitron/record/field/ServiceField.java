package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field delegating to a developer-provided service class via {@code @service}.
 */
public record ServiceField(String name, SourceLocation location) implements ChildField {}
