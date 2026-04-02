package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A mutation field for {@code @mutation(typeName: DELETE)}. Deleted rows are not re-queried.
 */
public record DeleteMutationField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements MutationField {}
