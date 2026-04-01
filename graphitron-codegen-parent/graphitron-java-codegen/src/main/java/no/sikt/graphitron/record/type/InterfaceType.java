package no.sikt.graphitron.record.type;

import graphql.language.SourceLocation;

/**
 * An interface with no directives whose implementing types each have {@code @table}.
 * Multi-table interface pattern.
 */
public record InterfaceType(String name, SourceLocation location) implements GraphitronType {}
