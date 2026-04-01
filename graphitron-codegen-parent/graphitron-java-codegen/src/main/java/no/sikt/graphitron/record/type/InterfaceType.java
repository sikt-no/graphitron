package no.sikt.graphitron.record.type;

import graphql.schema.GraphQLInterfaceType;

/**
 * An interface with no directives whose implementing types each have {@code @table}.
 * Multi-table interface pattern.
 */
public record InterfaceType(GraphQLInterfaceType definition) implements GraphitronType {}
