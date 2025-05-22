package no.fellesstudentsystem.schema_transformer.transform;

import graphql.schema.GraphQLSchema;

public interface ModifyingGraphQLTypeVisitor {

    GraphQLSchema getModifiedGraphQLSchema();

}
