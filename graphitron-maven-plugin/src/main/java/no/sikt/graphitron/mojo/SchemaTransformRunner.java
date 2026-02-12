package no.sikt.graphitron.mojo;

import graphql.schema.GraphQLSchema;
import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaReader;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes schema transformation for both the standalone transform goal and the generate goal.
 * <p>
 * Produces client-facing schemas and optionally a generator schema (for code generation).
 */
public class SchemaTransformRunner {
    public static final String DEFAULT_SCHEMA_FILENAME = "schema.graphql";
    private static final String GENERATOR_SCHEMA_FILENAME = "generator-schema.graphql";
    private static final String DIRECTIVES_RESOURCE = "/directives.graphqls";

    private final TransformPluginConfiguration config;

    public SchemaTransformRunner(TransformPluginConfiguration config) {
        this.config = config;
    }

    /**
     * Result of the schema transformation.
     *
     * @param generatorSchemaPath path to the generator schema, or null if not produced
     */
    public record Result(String generatorSchemaPath) {}

    /**
     * Transforms schemas and writes them to the output directory.
     * <p>
     * When {@code produceGeneratorSchema} is true (used by generate goal):
     * <ul>
     *   <li>Writes generator-schema.graphql for code generation</li>
     *   <li>Always writes schema.graphql as the default client schema</li>
     *   <li>Writes additional output schemas if configured</li>
     * </ul>
     * <p>
     * When {@code produceGeneratorSchema} is false (used by transform goal):
     * <ul>
     *   <li>Writes outputSchema if configured</li>
     *   <li>Writes outputSchemas if configured</li>
     * </ul>
     *
     * @param outputDirectory the directory to write transformed schemas to
     * @param produceGeneratorSchema whether to produce the generator schema
     * @return the result containing path to generator schema (if produced)
     * @throws IOException if an I/O error occurs
     */
    public Result execute(Path outputDirectory, boolean produceGeneratorSchema) throws IOException {
        Files.createDirectories(outputDirectory);

        var inputSchemaFiles = collectSchemaFiles(config.getSchemaRootDirectories());
        var descriptionSuffixForFeatures = createDescriptionSuffixMap(
                config.getSchemaRootDirectories(), config.getDescriptionSuffixFilename());

        String generatorSchemaPath = null;

        if (produceGeneratorSchema) {
            // Generate goal: produce generator schema + client schemas
            generatorSchemaPath = transformAndWriteGeneratorSchema(
                    outputDirectory, inputSchemaFiles, descriptionSuffixForFeatures);
            transformAndWriteClientSchemas(outputDirectory, inputSchemaFiles, descriptionSuffixForFeatures);
        } else {
            // Transform goal: only produce client schemas based on config
            transformAndWriteConfiguredSchemas(outputDirectory, inputSchemaFiles, descriptionSuffixForFeatures);
        }

        return new Result(generatorSchemaPath);
    }

    private String transformAndWriteGeneratorSchema(
            Path outputDirectory,
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) throws IOException {

        // Generator schema: NO feature flags, NO generator directive removal
        var generatorConfig = new TransformConfig(
                inputSchemaFiles,
                config.getDirectivesToRemove(),
                descriptionSuffixForFeatures,
                false,  // No feature flags for generator schema
                false,  // Keep generator directives
                true,  // Always exclude elements that the codegen should not generate.
                true);

        var generatorSchema = new SchemaTransformer(generatorConfig).transformSchema();
        SchemaWriter.writeSchemaToDirectory(
                generatorSchema, GENERATOR_SCHEMA_FILENAME, outputDirectory.toString(), false);

        return outputDirectory.resolve(GENERATOR_SCHEMA_FILENAME).toString();
    }

    private void transformAndWriteClientSchemas(
            Path outputDirectory,
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) throws IOException {

        var clientSchema = transformClientSchema(inputSchemaFiles, descriptionSuffixForFeatures);
        var outputDir = outputDirectory.toString();

        // Always write schema.graphql (full content, no feature filtering)
        SchemaWriter.writeSchemaToDirectory(
                clientSchema, DEFAULT_SCHEMA_FILENAME, outputDir, config.isRemoveFederationDefinitions());

        // Write additional output schemas if specified
        var outputSchemas = config.getOutputSchemas();
        if (outputSchemas != null && !outputSchemas.isEmpty()) {
            writeOutputSchemas(outputDir, clientSchema, outputSchemas);
        }
    }

    private void transformAndWriteConfiguredSchemas(
            Path outputDirectory,
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) throws IOException {

        var clientSchema = transformClientSchema(inputSchemaFiles, descriptionSuffixForFeatures);
        var outputDir = outputDirectory.toString();

        // Write single output schema if configured
        var outputSchema = config.getOutputSchema();
        if (outputSchema != null && !outputSchema.isEmpty()) {
            SchemaWriter.writeSchemaToDirectory(
                    clientSchema, outputSchema, outputDir, config.isRemoveFederationDefinitions());
        }

        // Write multiple output schemas if configured
        var outputSchemas = config.getOutputSchemas();
        if (outputSchemas != null && !outputSchemas.isEmpty()) {
            writeFilteredOutputSchemas(outputDir, clientSchema, outputSchemas);
        }
    }

    private GraphQLSchema transformClientSchema(
            List<String> inputSchemaFiles,
            Map<String, String> descriptionSuffixForFeatures) {

        var clientConfig = config.toTransformConfig(inputSchemaFiles, descriptionSuffixForFeatures);
        return new SchemaTransformer(clientConfig).transformSchema();
    }

    private void writeOutputSchemas(
            String outputDirectory,
            GraphQLSchema clientSchema,
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

        // Write filtered schemas through splitFeatures
        writeFilteredSchemas(outputDirectory, clientSchema, filteredSchemas);
    }

    private void writeFilteredOutputSchemas(
            String outputDirectory,
            GraphQLSchema clientSchema,
            Set<OutputSchema> outputSchemas) throws IOException {

        // All schemas go through feature filtering for transform goal
        writeFilteredSchemas(outputDirectory, clientSchema, outputSchemas);
    }

    private void writeFilteredSchemas(
            String outputDirectory,
            GraphQLSchema clientSchema,
            Set<OutputSchema> schemas) throws IOException {

        if (schemas.isEmpty()) {
            return;
        }

        // Group by federation setting
        var byFederationSetting = schemas.stream()
                .collect(Collectors.groupingBy(
                        o -> o.shouldRemoveFederationDefinitions(config.isRemoveFederationDefinitions()),
                        Collectors.toSet()));

        for (var entry : byFederationSetting.entrySet()) {
            var removeFed = entry.getKey();
            var schemaSet = entry.getValue();
            var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
            var features = SchemaTransformer.splitFeatures(reloadedSchema, schemaSet);
            for (var f : features) {
                SchemaWriter.writeSchemaToDirectory(
                        f.schema(), f.fileName(), outputDirectory, removeFed);
            }
        }
    }

    private List<String> collectSchemaFiles(Set<String> schemaRootDirectories) {
        var schemaFiles = new ArrayList<>(SchemaReader.findSchemaFilesRecursivelyInDirectory(schemaRootDirectories));
        schemaFiles.add(DIRECTIVES_RESOURCE);
        return schemaFiles;
    }

    private Map<String, String> createDescriptionSuffixMap(Set<String> schemaRootDirectories, String descriptionSuffixFilename) {
        if (descriptionSuffixFilename == null || descriptionSuffixFilename.isEmpty()) {
            return Map.of();
        }
        return SchemaReader.createDescriptionSuffixForFeatureMap(schemaRootDirectories, descriptionSuffixFilename);
    }
}
