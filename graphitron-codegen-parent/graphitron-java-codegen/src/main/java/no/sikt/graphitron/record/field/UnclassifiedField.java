package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * A field that does not match any known type. A schema containing unclassified fields is invalid —
 * Graphitron terminates with an error identifying which fields need to be fixed.
 */
public record UnclassifiedField(GraphQLFieldDefinition definition) implements GraphitronField {}
