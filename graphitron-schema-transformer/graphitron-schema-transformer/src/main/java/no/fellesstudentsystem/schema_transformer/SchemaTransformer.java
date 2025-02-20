package no.fellesstudentsystem.schema_transformer;

import graphql.schema.GraphQLSchema;
import graphql.schema.GraphQLType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import no.fellesstudentsystem.schema_transformer.transform.FeatureFlagConfiguration;
import no.fellesstudentsystem.schema_transformer.transform.GeneratorDirectivesFilter;
import no.fellesstudentsystem.schema_transformer.transform.SchemaFeatureFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static no.fellesstudentsystem.schema_transformer.schema.SchemaReader.getTypeDefinitionRegistry;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.assembleSchema;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeFederationSchemaToDirectory;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToDirectory;
import static no.fellesstudentsystem.schema_transformer.schema.SchemaWriter.writeSchemaToString;

public class SchemaTransformer {
    public final static String
            GENERATOR_SCHEMA_NAME = "generator-schema.graphql",
            SCHEMA_NAME = "schema.graphql",
            FEDERATION_SCHEMA_NAME = "federation-schema.graphql";

    public static void transformFeatures(List<String> schemaLocations, Map<String, String> descriptionSuffixForFeature, String outputDirectory) throws IOException {
        transformFeatures(schemaLocations, descriptionSuffixForFeature, outputDirectory, true);
    }

    public static void transformFeatures(List<String> schemaLocations, Map<String, String> descriptionSuffixForFeature, String outputDirectory, boolean reifyContracts) throws IOException {
        var typeRegistry = getTypeDefinitionRegistry(schemaLocations);
        var schema = assembleSchema(typeRegistry);

        writeSchemaToDirectory(schema, GENERATOR_SCHEMA_NAME, outputDirectory, true);

        var schemaWithFeatureFlags = new FeatureFlagConfiguration(schema, descriptionSuffixForFeature).getModifiedGraphQLSchema();
        var schemaWithoutGeneratorDirectives = new GeneratorDirectivesFilter(schemaWithFeatureFlags).getModifiedGraphQLSchema();

        writeSchemaToDirectory(schemaWithoutGeneratorDirectives, SCHEMA_NAME, outputDirectory);

        if (reifyContracts) {
            // Vi må laste skjemaet på nytt, ellers får vi NPE.
            var s = loadSchema(writeSchemaToString(schemaWithoutGeneratorDirectives, false));
            var prodSchema = new SchemaFeatureFilter(Set.of()).getFilteredGraphQLSchema(s);
            writeSchemaToDirectory(prodSchema, "schema-prod.graphql", outputDirectory);

            var betaSchema = new SchemaFeatureFilter(Set.of("beta")).getFilteredGraphQLSchema(s);
            writeSchemaToDirectory(betaSchema, "schema-beta.graphql", outputDirectory);

            var expSchema = new SchemaFeatureFilter(Set.of("beta", "experimental")).getFilteredGraphQLSchema(s);
            writeSchemaToDirectory(expSchema, "schema-exp.graphql", outputDirectory);

            writeFederationSchemaToDirectory(s, FEDERATION_SCHEMA_NAME, outputDirectory);
        }
    }

    private static GraphQLSchema loadSchema(String schemaString) throws IOException {
        var typeDefinitionRegistry = new SchemaParser().parse(schemaString);
        var parsedSchema = UnExecutableSchemaGenerator.makeUnExecutableSchema(typeDefinitionRegistry);
        return flattenSchema(parsedSchema);
    }

    private static GraphQLSchema flattenSchema(GraphQLSchema parsedSchema) {
        return parsedSchema.transform(builder -> {
            var excludedTypeNames = Set.of("Query", "Mutation", "Subscription");
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
