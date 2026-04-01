package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A field bound to a column on a table joined from the source table.
 */
public record ColumnReferenceField(String name, SourceLocation location) implements ChildField {}
