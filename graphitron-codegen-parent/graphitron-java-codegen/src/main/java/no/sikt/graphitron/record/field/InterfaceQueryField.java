package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A root query field targeting a multi-table interface — the interface has no directives;
 * implementing types each have {@code @table}.
 */
public record InterfaceQueryField(GraphQLFieldDefinition definition) implements QueryField {}
