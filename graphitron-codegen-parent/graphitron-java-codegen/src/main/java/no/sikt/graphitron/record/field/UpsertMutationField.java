package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A mutation field for {@code @mutation(typeName: UPSERT)}.
 */
public record UpsertMutationField(String name, SourceLocation location) implements MutationField {}
