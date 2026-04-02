package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * The {@code Query.node(id:)} field for Relay Global Object Identification.
 */
public record NodeQueryField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements QueryField {}
