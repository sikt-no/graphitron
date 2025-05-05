package no.fellesstudentsystem.schema_transformer;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.ScalarTypeDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.EchoingWiringFactory;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.ScalarInfo;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import no.fellesstudentsystem.schema_transformer.transform.DirectivesFilter;
import no.fellesstudentsystem.schema_transformer.transform.FeatureFlagConfiguration;
import no.fellesstudentsystem.schema_transformer.transform.MakeConnections;
import no.fellesstudentsystem.schema_transformer.transform.MergeExtensions;
import no.fellesstudentsystem.schema_transformer.transform.SchemaFeatureFilter;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static graphql.schema.idl.EchoingWiringFactory.fakeScalar;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.CONNECTION;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToString;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_MUTATION;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_QUERY;
import static no.sikt.graphql.naming.GraphQLReservedName.SCHEMA_SUBSCRIPTION;

public class SchemaTransformer {
    private final TransformConfig config;

    public SchemaTransformer(TransformConfig config) {
        this.config = config;
    }

    public GraphQLSchema transformSchema() {
        var registry = getTypeDefinitionRegistry(config.schemaLocations());
        getRegistryTransforms().forEach(it -> it.accept(registry));
        var schema = assembleSchema(registry);
        var transforms = getSchemaTransforms();
        for (var t : transforms) {
            schema = t.apply(schema);
        }

        return schema;
    }

    private List<Consumer<TypeDefinitionRegistry>> getRegistryTransforms() {
        var transforms = new ArrayList<Consumer<TypeDefinitionRegistry>>();
        transforms.add(MergeExtensions::transform);
        if (config.expandConnections()) {
            transforms.add(MakeConnections::transform);
        }
        return transforms;
    }

    private List<Function<GraphQLSchema, GraphQLSchema>> getSchemaTransforms() {
        var transforms = new ArrayList<Function<GraphQLSchema, GraphQLSchema>>();

        if (config.addFeatureFlags()) {
            transforms.add((s) -> new FeatureFlagConfiguration(s, config.descriptionSuffixForFeatures()).getModifiedGraphQLSchema());
        }

        var filterDirectives = new HashSet<>(config.directivesToFilter());
        filterDirectives.add(CONNECTION.getName()); // Must be done after the connection transform. Potentially deprecated.
        if (config.removeGeneratorDirectives()) {
            filterDirectives.addAll(Arrays.stream(GenerationDirective.values()).map(GenerationDirective::getName).collect(Collectors.toSet()));
        }

        if (!filterDirectives.isEmpty()) {
            transforms.add((s) -> new DirectivesFilter(s, filterDirectives).getModifiedGraphQLSchema());
        }

        return transforms;
    }

    public static List<FeatureSchema> splitFeatures(GraphQLSchema schema, Set<OutputSchema> features) {
        return features
                .stream()
                .map(it -> new FeatureSchema(it.fileName(), new SchemaFeatureFilter(it.flags()).getFilteredGraphQLSchema(schema)))
                .toList();
    }

    /**
     * Assembles a GraphQL schema from the provided type definition registry.
     * This method validates the schema and ensures it is executable by applying
     * the necessary runtime wiring. Optionally, it can add Apollo Federation
     * support to the schema.
     *
     * <p>The runtime wiring is minimal and is only used for schema validation.
     * It includes support for custom scalar types that are not part of the
     * GraphQL specification. If Apollo federation is imported, the method applies
     * federation types and directives to the schema, ensuring it is valid
     * without requiring federation definitions in the input schemas.</p>
     *
     * @param typeDefinitionRegistry the registry containing type definitions for the schema
     * @return an executable GraphQL schema
     * @throws IllegalArgumentException if the schema cannot be assembled due to validation errors
     */
    public static GraphQLSchema assembleSchema(TypeDefinitionRegistry typeDefinitionRegistry) {

        // The wiring is extracted from graphql.schema.idl.UnExecutableSchemaGenerator
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring -> {
            Map<String, ScalarTypeDefinition> scalars = typeDefinitionRegistry.scalars();
            scalars.forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(fakeScalar(name));
                }
            });
        });

        if (federationIsImported(typeDefinitionRegistry)) {
            return assembleSchemaWithFederation(typeDefinitionRegistry, runtimeWiring);
        } else {
            return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
        }
    }

    private static boolean federationIsImported(TypeDefinitionRegistry typeDefinitionRegistry) {
        return LinkDirectiveProcessor.loadFederationImportedDefinitions(typeDefinitionRegistry) != null;
    }

    private static GraphQLSchema assembleSchemaWithFederation(TypeDefinitionRegistry typeDefinitionRegistry, RuntimeWiring runtimeWiring) {
        return Federation
                .transform(typeDefinitionRegistry, runtimeWiring)
                .setFederation2(true)
                .resolveEntityType((env) -> null) // Hack because the transform demands these to be present.
                .fetchEntities((env) -> null)
                .build();
    }

    public static GraphQLSchema reloadSchema(GraphQLSchema s, boolean removeFederationDefinitions) {
        var parsedSchema = assembleSchema(new SchemaParser().parse(writeSchemaToString(s, removeFederationDefinitions)));
        return parsedSchema.transform(builder -> {
            var excludedTypeNames = Set.of(SCHEMA_QUERY.getName(), SCHEMA_MUTATION.getName(), SCHEMA_SUBSCRIPTION.getName());
            Set<GraphQLType> additionalTypes = parsedSchema
                    .getAllTypesAsList()
                    .stream()
                    .filter(t -> !excludedTypeNames.contains(t.getName()))
                    .collect(Collectors.toSet());

            builder.clearAdditionalTypes();
            builder.additionalTypes(additionalTypes);
        });
    }
}
