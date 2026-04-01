package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A scalar or nested property read from a result-mapped source. No SQL generated.
 */
public record PropertyField(String name, SourceLocation location) implements ChildField {}
