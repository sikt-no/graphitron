package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * The {@code Query._entities(representations:)} field for Apollo Federation.
 */
public record EntityQueryField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements QueryField {}
