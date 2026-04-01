package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A child field whose return type is a multi-table interface.
 */
public record InterfaceField(String name, SourceLocation location) implements ChildField {}
