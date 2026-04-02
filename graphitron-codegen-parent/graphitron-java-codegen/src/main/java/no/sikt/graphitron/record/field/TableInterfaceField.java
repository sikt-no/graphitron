package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is a single-table interface.
 *
 * <p>{@code cardinality} is the cardinality of this field.
 */
public record TableInterfaceField(
    String parentTypeName,
    String name,
    SourceLocation location,
    FieldCardinality cardinality
) implements ChildField {}
