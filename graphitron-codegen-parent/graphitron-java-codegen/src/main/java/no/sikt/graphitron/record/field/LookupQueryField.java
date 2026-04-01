package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * Triggered by {@code @lookupKey} on one or more arguments.
 */
public record LookupQueryField(String name, SourceLocation location) implements QueryField {}
