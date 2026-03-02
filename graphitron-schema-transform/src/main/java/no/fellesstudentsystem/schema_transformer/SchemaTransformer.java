package no.fellesstudentsystem.schema_transformer;

import com.apollographql.federation.graphqljava.Federation;
import com.apollographql.federation.graphqljava.directives.LinkDirectiveProcessor;
import graphql.language.ScalarTypeDefinition;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.*;
import no.fellesstudentsystem.schema_transformer.transform.*;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static graphql.schema.idl.EchoingWiringFactory.fakeScalar;
import static no.fellesstudentsystem.schema_transformer.TransformConfig.DIRECTIVES_FOR_REMOVING_ELEMENTS;
import static no.fellesstudentsystem.schema_transformer.mapping.GraphQLDirective.CONNECTION;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToString;
import static no.sikt.graphql.naming.GraphQLReservedName.*;

public class SchemaTransformer {
    private final TransformConfig config;

    public SchemaTransformer(TransformConfig config) {
        this.config = config;
    }

    public GraphQLSchema transformSchema() {
        var registry = getTypeDefinitionRegistry(config.schemaLocations());
        getRegistryTransforms().forEach(it -> it.accept(registry));
        var schema = assembleSchema(registry);
        var transforms = getSchemaTransforms(registry);
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

    private List<Function<GraphQLSchema, GraphQLSchema>> getSchemaTransforms(TypeDefinitionRegistry registry) {
        var transforms = new ArrayList<Function<GraphQLSchema, GraphQLSchema>>();
        var disabledConnectionFields = getDisabledConnectionFields();

        // This one goes first since removing fields and types allows us to not process them in later transforms.
        if (config.removeExcludedElements()) {
            transforms.add((s) -> new ElementRemovalFilter(s, DIRECTIVES_FOR_REMOVING_ELEMENTS).getModifiedGraphQLSchema());
        }

        if (config.addFeatureFlags()) {
            transforms.add((s) -> new FeatureConfiguration(s, config.descriptionSuffixForFeatures(), federationIsImported(registry)).getModifiedGraphQLSchema());
        }

        var filterDirectives = new HashSet<>(config.directivesToFilter());
        filterDirectives.add(CONNECTION.getName()); // Must be done after the connection transform. Potentially deprecated.
        if (config.removeGeneratorDirectives()) {
            filterDirectives.addAll(Arrays.stream(GenerationDirective.values()).map(GenerationDirective::getName).collect(Collectors.toSet()));
        }

        if (!filterDirectives.isEmpty()) {
            transforms.add((s) -> new DirectivesFilter(s, filterDirectives).getModifiedGraphQLSchema());
        }

        if (!disabledConnectionFields.isEmpty()) {
            transforms.add(schema -> new ConnectionFieldFilter(schema, disabledConnectionFields)
                    .getModifiedGraphQLSchema()
            );
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
        var registry = new SchemaParser().parse(writeSchemaToString(s, removeFederationDefinitions));
        // Always use assembleSchemaWithoutFederation when reloading:
        // - When removeFederationDefinitions=true: federation types are removed from the SDL,
        //   but @link may still be present, so Federation.transform() would fail
        // - When removeFederationDefinitions=false: federation types are already in the SDL,
        //   so we don't need to add them again via Federation.transform()
        var parsedSchema = assembleSchemaWithoutFederation(registry);
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

    private static GraphQLSchema assembleSchemaWithoutFederation(TypeDefinitionRegistry typeDefinitionRegistry) {
        var runtimeWiring = EchoingWiringFactory.newEchoingWiring(wiring -> {
            Map<String, ScalarTypeDefinition> scalars = typeDefinitionRegistry.scalars();
            scalars.forEach((name, v) -> {
                if (!ScalarInfo.isGraphqlSpecifiedScalar(name)) {
                    wiring.scalar(fakeScalar(name));
                }
            });
        });
        return new SchemaGenerator().makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);
    }

    /**
     * Determines which connection fields should be disabled based on the configuration. The {@code nodes} and
     * {@code totalCount} fields are not part of the Relay Connection specification, so they can be disabled if desired.
     *
     * @return A set of connection field names to be disabled.
     */
    private Set<String> getDisabledConnectionFields() {
        var disabledFields = new HashSet<String>();

        if (!config.nodesFieldInConnectionsEnabled()) {
            disabledFields.add("nodes");
        }
        if (!config.totalCountFieldInConnectionsEnabled()) {
            disabledFields.add("totalCount");
        }

        return disabledFields;
    }
}
