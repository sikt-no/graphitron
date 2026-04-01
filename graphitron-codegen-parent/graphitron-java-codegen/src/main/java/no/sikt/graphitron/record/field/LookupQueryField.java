package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** A root query field triggered by {@code @lookupKey} on an argument. Table-mapped target. */
public record LookupQueryField(GraphQLFieldDefinition definition) implements QueryField {}
