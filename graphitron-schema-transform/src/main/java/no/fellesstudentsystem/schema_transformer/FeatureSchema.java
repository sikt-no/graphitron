package no.fellesstudentsystem.schema_transformer;

import graphql.schema.GraphQLSchema;

public record FeatureSchema(String fileName, GraphQLSchema schema) {}
