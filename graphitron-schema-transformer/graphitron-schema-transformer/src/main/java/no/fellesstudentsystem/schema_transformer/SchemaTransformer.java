package no.fellesstudentsystem.schema_transformer;

import com.apollographql.federation.graphqljava.Federation;
import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import no.fellesstudentsystem.schema_transformer.transform.*;
import no.sikt.graphql.directives.GenerationDirective;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

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
        if (config.addFederation()) {
            transforms.add(SchemaTransformer::addFederation);
        }
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

    public static GraphQLSchema addFederation(GraphQLSchema schema) {
        return Federation
                .transform(schema)
                .setFederation2(true)
                .resolveEntityType((env) -> null) // Hack because the transform demands these to be present.
                .fetchEntities((env) -> null)
                .build();
    }

    /**
     * Will detect and throw exceptions if there are problems in assembling a valid schema,
     * such as missing/invalid directive arguments.
     * The runtimeWiring is only used to validate the schema and is thus incomplete
     * (e.g. the type resolvers are just dummies), it contains a bare minimum needed for validation.
     *
     * @return an executable schema
     */
    public static GraphQLSchema assembleSchema(TypeDefinitionRegistry typeDefinitionRegistry) {
        return UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry);
    }

    public static GraphQLSchema reloadSchema(GraphQLSchema s) {
        var parsedSchema = assembleSchema(new SchemaParser().parse(writeSchemaToString(s)));
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
