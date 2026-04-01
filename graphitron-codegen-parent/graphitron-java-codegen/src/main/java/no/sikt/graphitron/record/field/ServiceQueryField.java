package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A root query field with {@code @service}. Executes in a private scope. */
public record ServiceQueryField(GraphQLFieldDefinition definition) implements QueryField {}
