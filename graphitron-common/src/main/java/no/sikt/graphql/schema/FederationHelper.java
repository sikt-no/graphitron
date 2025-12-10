package no.sikt.graphql.schema;

import com.apollographql.federation.graphqljava.Federation;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.TypeResolver;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.TypeDefinitionRegistry;

/**
 * Helper class for building Apollo Federation schemas.
 * This class provides convenient methods for transforming a GraphQL schema
 * into a Federation 2 compatible schema.
 */
public class FederationHelper {

    /**
     * Builds a Federation 2 compatible GraphQL schema without entity resolution.
     * Use this method when you want federation support (e.g., @shareable, @external directives)
     * but don't have any resolvable entities in the schema.
     */
    public static GraphQLSchema buildFederatedSchema(
            TypeDefinitionRegistry registry,
            RuntimeWiring.Builder wiringBuilder
    ) {
        return Federation
                .transform(registry, wiringBuilder.build())
                .setFederation2(true)
                .build();
    }

    /**
     * Builds a Federation 2 compatible GraphQL schema with entity resolution.
     * Use this method when your schema has resolvable entities (types with @key directive
     * and resolvable: true).
     */
    public static GraphQLSchema buildFederatedSchema(
            TypeDefinitionRegistry registry,
            RuntimeWiring.Builder wiringBuilder,
            TypeResolver entityTypeResolver,
            DataFetcher<?> entityFetcher
    ) {
        return Federation
                .transform(registry, wiringBuilder.build())
                .setFederation2(true)
                .resolveEntityType(entityTypeResolver)
                .fetchEntities(entityFetcher)
                .build();
    }
}
