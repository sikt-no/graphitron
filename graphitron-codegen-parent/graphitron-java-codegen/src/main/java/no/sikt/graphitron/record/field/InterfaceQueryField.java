package no.sikt.graphitron.record.field;

import graphql.language.SourceLocation;

/**
 * A root query field whose return type is a multi-table interface.
 */
public record InterfaceQueryField(String name, SourceLocation location) implements QueryField {}
