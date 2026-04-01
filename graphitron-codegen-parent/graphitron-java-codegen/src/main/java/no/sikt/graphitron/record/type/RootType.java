package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * A root operation type (Query or Mutation). Unmapped — no source context, no SQL until
 * a scope is entered via a child field.
 */
public record RootType(String name, SourceLocation location) implements GraphitronType {}
