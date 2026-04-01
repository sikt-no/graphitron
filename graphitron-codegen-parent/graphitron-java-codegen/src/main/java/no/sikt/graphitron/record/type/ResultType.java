package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * A type annotated with {@code @record}. Runtime wiring only — no SQL until a new scope starts.
 */
public record ResultType(String name, SourceLocation location) implements GraphitronType {}
