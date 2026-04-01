package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A mutation field for {@code @mutation(typeName: INSERT)}.
 */
public record InsertMutationField(String name, SourceLocation location) implements MutationField {}
