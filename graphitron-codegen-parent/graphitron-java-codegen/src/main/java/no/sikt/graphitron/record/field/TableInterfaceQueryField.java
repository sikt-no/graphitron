package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A root query field targeting a single-table interface — the interface has {@code @table} and
 * {@code @discriminate}; implementing types have {@code @table} and {@code @discriminator}.
 */
public record TableInterfaceQueryField(GraphQLFieldDefinition definition) implements QueryField {}
