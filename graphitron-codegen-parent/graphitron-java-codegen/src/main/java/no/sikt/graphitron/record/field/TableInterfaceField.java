package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is a single-table interface.
 */
public record TableInterfaceField(String name, SourceLocation location) implements ChildField {}
