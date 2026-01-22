package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes schema transformation for the generate-all goal.
 */
public class GenerateAllExecutor {
    private static final String GENERATOR_SCHEMA_FILENAME = "generator-schema.graphql";

    private final Config config;

    public GenerateAllExecutor(Config config) {
        this.config = config;
    }

    /**
     * Configuration for the executor.
     */
    public record Config(
            Set<String> schemaRootDirectories,
            String descriptionSuffixFilename,
            boolean removeGeneratorDirectives,
            boolean removeFederationDefinitions,
            boolean expandConnections,
            boolean addFeatureFlags,
            Set<OutputSchema> outputSchemas,
            Set<String> directivesToRemove
    ) {
        public Config {
            if (directivesToRemove == null) {
                directivesToRemove = Set.of();
            }
        }
    }

    /**
     * Result of the schema transformation.
     */
    public record Result(
            String generatorSchemaPath
    ) {}

    /**
     * Transforms schemas and writes them to the output directory.
     *
     * @param outputDirectory the directory to write transformed schemas to
     * @return the result containing paths to generated files
     * @throws IOException if an I/O error occurs
     */
    public Result execute(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);

        var inputSchemaFiles = SchemaTransformHelper.collectSchemaFiles(config.schemaRootDirectories());
        var descriptionSuffixForFeatures = SchemaTransformHelper.createDescriptionSuffixMap(
                config.schemaRootDirectories(), config.descriptionSuffixFilename());

        // 1. Transform and write generator-schema.graphql (for code generation)
        // Generator schema: NO feature flags, NO generator directive removal, NO federation removal
        var generatorSchemaPath = transformAndWriteGeneratorSchema(
                outputDirectory, inputSchemaFiles, descriptionSuffixForFeatures);

        // 2. Transform and write client-facing schemas
        transformAndWriteClientSchemas(outputDirectory, inputSchemaFiles, descriptionSuffixForFeatures);

        return new Result(generatorSchemaPath);
    }

    private String transformAndWriteGeneratorSchema(
            Path outputDirectory,
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) throws IOException {

        var generatorConfig = new TransformConfig(
                inputSchemaFiles,
                config.directivesToRemove(),
                descriptionSuffixForFeatures,
                false,  // No feature flags for generator schema
                false,  // Keep generator directives
                config.expandConnections());

        var generatorSchema = new SchemaTransformer(generatorConfig).transformSchema();
        SchemaWriter.writeSchemaToDirectory(
                generatorSchema, GENERATOR_SCHEMA_FILENAME, outputDirectory.toString(), false);

        return outputDirectory.resolve(GENERATOR_SCHEMA_FILENAME).toString();
    }

    private void transformAndWriteClientSchemas(
            Path outputDirectory,
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) throws IOException {

        var clientConfig = new TransformConfig(
                inputSchemaFiles,
                config.directivesToRemove(),
                descriptionSuffixForFeatures,
                config.addFeatureFlags(),
                config.removeGeneratorDirectives(),
                config.expandConnections());

        var clientSchema = new SchemaTransformer(clientConfig).transformSchema();

        if (config.outputSchemas() != null && !config.outputSchemas().isEmpty()) {
            writeMultipleOutputSchemas(outputDirectory, clientSchema);
        } else {
            // Default: produce a single schema.graphql for clients
            SchemaWriter.writeSchemaToDirectory(
                    clientSchema, "schema.graphql", outputDirectory.toString(), config.removeFederationDefinitions());
        }
    }

    private void writeMultipleOutputSchemas(
            Path outputDirectory,
            graphql.schema.GraphQLSchema clientSchema) throws IOException {

        // Separate schemas that include all features (no filtering) from those that need feature filtering
        var unfilteredSchemas = config.outputSchemas().stream()
                .filter(OutputSchema::includeAllFeatures)
                .collect(Collectors.toSet());
        var filteredSchemas = config.outputSchemas().stream()
                .filter(o -> !o.includeAllFeatures())
                .collect(Collectors.toSet());

        // Write unfiltered schemas directly (no splitFeatures)
        for (var schema : unfilteredSchemas) {
            var removeFed = schema.shouldRemoveFederationDefinitions(config.removeFederationDefinitions());
            var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
            SchemaWriter.writeSchemaToDirectory(
                    reloadedSchema, schema.fileName(), outputDirectory.toString(), removeFed);
        }

        // Process filtered schemas through splitFeatures, grouped by federation setting
        if (!filteredSchemas.isEmpty()) {
            var byFederationSetting = filteredSchemas.stream()
                    .collect(Collectors.groupingBy(
                            o -> o.shouldRemoveFederationDefinitions(config.removeFederationDefinitions()),
                            Collectors.toSet()));

            for (var entry : byFederationSetting.entrySet()) {
                var removeFed = entry.getKey();
                var schemas = entry.getValue();
                var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
                var features = SchemaTransformer.splitFeatures(reloadedSchema, schemas);
                for (var f : features) {
                    SchemaWriter.writeSchemaToDirectory(
                            f.schema(), f.fileName(), outputDirectory.toString(), removeFed);
                }
            }
        }
    }
}
