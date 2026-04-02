package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is a multi-table interface.
 *
 * <p>{@code cardinality} is the cardinality of this field.
 */
public record InterfaceQueryField(String name, SourceLocation location, FieldCardinality cardinality) implements QueryField {}
