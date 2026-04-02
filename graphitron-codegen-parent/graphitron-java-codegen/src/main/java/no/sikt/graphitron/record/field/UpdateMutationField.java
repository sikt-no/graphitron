package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A mutation field for {@code @mutation(typeName: UPDATE)}.
 */
public record UpdateMutationField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements MutationField {}
