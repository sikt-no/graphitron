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
 * Produces both a generator-schema (for code generation) and client-facing schemas.
 */
public class GenerateAllExecutor {
    public static final String DEFAULT_SCHEMA_FILENAME = "schema.graphql";
    private static final String GENERATOR_SCHEMA_FILENAME = "generator-schema.graphql";


    private final TransformConfiguration config;

    public GenerateAllExecutor(TransformConfiguration config) {
        this.config = config;
    }

    /**
     * Result of the schema transformation.
     */
    public record Result(String generatorSchemaPath) {}

    /**
     * Transforms schemas and writes them to the output directory.
     *
     * @param outputDirectory the directory to write transformed schemas to
     * @return the result containing paths to generated files
     * @throws IOException if an I/O error occurs
     */
    public Result execute(Path outputDirectory) throws IOException {
        Files.createDirectories(outputDirectory);

        var inputSchemaFiles = SchemaTransformHelper.collectSchemaFiles(config.getSchemaRootDirectories());
        var descriptionSuffixForFeatures = SchemaTransformHelper.createDescriptionSuffixMap(
                config.getSchemaRootDirectories(), config.getDescriptionSuffixFilename());

        // 1. Transform and write generator-schema.graphql (for code generation)
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

        // Generator schema: NO feature flags, NO generator directive removal, NO federation removal
        var generatorConfig = new TransformConfig(
                inputSchemaFiles,
                config.getDirectivesToRemove(),
                descriptionSuffixForFeatures,
                false,  // No feature flags for generator schema
                false,  // Keep generator directives
                config.isExpandConnections());

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
                config.getDirectivesToRemove(),
                descriptionSuffixForFeatures,
                config.isAddFeatureFlags(),
                config.isRemoveGeneratorDirectives(),
                config.isExpandConnections());

        var clientSchema = new SchemaTransformer(clientConfig).transformSchema();
        var outputDirectory_str = outputDirectory.toString();

        // Always write schema.graphql (full content, no feature filtering)
        SchemaWriter.writeSchemaToDirectory(
                clientSchema, DEFAULT_SCHEMA_FILENAME, outputDirectory_str, config.isRemoveFederationDefinitions());

        // Write additional output schemas if specified
        var outputSchemas = config.getOutputSchemas();
        if (outputSchemas != null && !outputSchemas.isEmpty()) {
            writeOutputSchemas(outputDirectory_str, clientSchema, outputSchemas);
        }
    }

    private void writeOutputSchemas(
            String outputDirectory,
            graphql.schema.GraphQLSchema clientSchema,
            Set<OutputSchema> outputSchemas) throws IOException {

        // Separate schemas into those that include all features vs those that need filtering
        var fullSchemas = outputSchemas.stream()
                .filter(OutputSchema::includeAllFeatures)
                .collect(Collectors.toSet());
        var filteredSchemas = outputSchemas.stream()
                .filter(o -> !o.includeAllFeatures())
                .collect(Collectors.toSet());

        // Write full schemas directly (no feature filtering)
        for (var schema : fullSchemas) {
            var removeFed = schema.shouldRemoveFederationDefinitions(config.isRemoveFederationDefinitions());
            var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
            SchemaWriter.writeSchemaToDirectory(
                    reloadedSchema, schema.fileName(), outputDirectory, removeFed);
        }

        // Write filtered schemas through splitFeatures, grouped by federation setting
        if (!filteredSchemas.isEmpty()) {
            var byFederationSetting = filteredSchemas.stream()
                    .collect(Collectors.groupingBy(
                            o -> o.shouldRemoveFederationDefinitions(config.isRemoveFederationDefinitions()),
                            Collectors.toSet()));

            for (var entry : byFederationSetting.entrySet()) {
                var removeFed = entry.getKey();
                var schemas = entry.getValue();
                var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
                var features = SchemaTransformer.splitFeatures(reloadedSchema, schemas);
                for (var f : features) {
                    SchemaWriter.writeSchemaToDirectory(
                            f.schema(), f.fileName(), outputDirectory, removeFed);
                }
            }
        }
    }
}
