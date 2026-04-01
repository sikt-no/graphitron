package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field delegating to a developer-provided service class via {@code @service}.
 */
public record ServiceQueryField(String name, SourceLocation location) implements QueryField {}
