package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A mutation field delegating to a developer-provided service class via {@code @service}.
 */
public record ServiceMutationField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements MutationField {}
