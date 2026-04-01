package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * Reads a scalar or nested record property from a result-mapped source. Generates a trivial
 * data fetcher. No SQL interaction. Result-mapped source context only.
 */
public record PropertyField(GraphQLFieldDefinition definition) implements ChildField {}
