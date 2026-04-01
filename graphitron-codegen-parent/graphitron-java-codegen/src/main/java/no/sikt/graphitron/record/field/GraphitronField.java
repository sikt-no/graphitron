package no.sikt.graphitron.record.field;

import graphql.schema.GraphQLFieldDefinition;

/**
 * Classifies every field in a GraphQL schema. The sealed hierarchy mirrors the field taxonomy.
 * Every leaf type is a Java record carrying the properties needed for code generation.
 */
public sealed interface GraphitronField
    permits RootField, ChildField, NotGeneratedField, UnclassifiedField {

    GraphQLFieldDefinition definition();

    default String name() {
        return definition().getName();
    }
}
