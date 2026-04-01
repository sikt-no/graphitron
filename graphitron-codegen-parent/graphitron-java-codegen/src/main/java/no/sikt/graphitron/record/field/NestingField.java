package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field that inherits the source table context without introducing a new scope boundary.
 */
public record NestingField(String name, SourceLocation location) implements ChildField {}
