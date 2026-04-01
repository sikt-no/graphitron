package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is annotated with {@code @table}.
 */
public record TableField(String name, SourceLocation location) implements ChildField {}
