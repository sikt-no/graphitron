package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is annotated with {@code @table}.
 */
public record TableQueryField(String name, SourceLocation location) implements QueryField {}
