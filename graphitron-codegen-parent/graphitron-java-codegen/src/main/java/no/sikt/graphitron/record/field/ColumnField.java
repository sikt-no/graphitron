package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A scalar or enum field bound to a column on the source table.
 */
public record ColumnField(String name, SourceLocation location) implements ChildField {}
