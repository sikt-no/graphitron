package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/** {@code Query._entities(representations:)} — Apollo Federation entity resolver. */
public record EntityQueryField(GraphQLFieldDefinition definition) implements QueryField {}
