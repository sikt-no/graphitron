package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field mapped via a constructor parameter on a result record.
 */
public record ConstructorField(
    String parentTypeName,
    String name,
    SourceLocation location
) implements ChildField {}
