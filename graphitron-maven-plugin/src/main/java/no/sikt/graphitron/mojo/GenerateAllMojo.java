package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.fellesstudentsystem.schema_transformer.SchemaTransformer;
import no.fellesstudentsystem.schema_transformer.TransformConfig;
import no.fellesstudentsystem.schema_transformer.schema.SchemaWriter;
import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.OptionalSelect;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Combined Mojo that performs schema transformation and code generation in a single execution.
 * This simplifies the setup for new subgraphs by eliminating the need for separate transform and generate executions.
 */
@Mojo(name = "generate-all", defaultPhase = GENERATE_SOURCES)
public class GenerateAllMojo extends AbstractMojo implements Generator {
    private static final String TRANSFORM_TARGET_PATH = "target/generated-resources/graphql_transformer/";
    private static final String GENERATOR_SCHEMA_FILENAME = "generator-schema.graphql";

    // ============================================
    // Maven Project
    // ============================================

    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    // ============================================
    // Transform Parameters
    // ============================================

    /**
     * Directories to search for GraphQL schema files. The plugin will process all schema files found
     * in these directories and their subdirectories.
     */
    @Parameter(property = "schemaRootDirectories", required = true)
    private Set<String> schemaRootDirectories;

    /**
     * Name of the file containing description suffixes to be added to schema elements based on their feature flags.
     */
    @Parameter(property = "descriptionSuffixFilename", defaultValue = "description-suffix.md")
    private String descriptionSuffixFilename;

    /**
     * Whether to remove generator directives from the client-facing output schemas.
     * Generator directives are always preserved in the internal generator-schema.graphql used for code generation.
     */
    @Parameter(property = "removeGeneratorDirectives", defaultValue = "true")
    private boolean removeGeneratorDirectives;

    /**
     * Whether to remove all Apollo federation directives and types from the schema.
     */
    @Parameter(property = "removeFederationDefinitions", defaultValue = "false")
    private boolean removeFederationDefinitions;

    /**
     * Whether to expand GraphQL connection types into full GraphQL Cursor Connections Specification-compliant structures.
     */
    @Parameter(property = "expandConnections", defaultValue = "true")
    private boolean expandConnections;

    /**
     * Whether to add feature flags to the schema based on directory structure.
     */
    @Parameter(property = "addFeatureFlags", defaultValue = "false")
    private boolean addFeatureFlags;

    /**
     * Configuration for multiple output schemas with different feature flags.
     * If not specified, a single schema.graphql will be produced for clients.
     */
    @Parameter(property = "outputSchemas")
    private Set<OutputSchema> outputSchemas;

    /**
     * Set of directive names to remove from the output schema.
     */
    @Parameter(property = "directivesToRemove")
    private Set<String> directivesToRemove;

    // ============================================
    // Generate Parameters
    // ============================================

    /**
     * The location where the generated code should be exported to.
     */
    @Parameter(property = "outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The package where the generated code should be exported to.
     */
    @Parameter(property = "outputPackage", defaultValue = "no.sikt.graphql")
    private String outputPackage;

    /**
     * The comma-separated locations of the schema files to provide to the user.
     * If not specified, defaults to schema.graphql in the transform output directory.
     */
    @Parameter(property = "userSchemaFiles")
    private Set<String> userSchemaFiles;

    /**
     * The output folder for jOOQ generated code.
     */
    @Parameter(property = "jooqGeneratedPackage")
    private String jooqGeneratedPackage;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;

    /**
     * Extra scalars that can be used in code generation.
     */
    @Parameter(property = "scalars")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> scalars;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "globalRecordTransforms")
    @SuppressWarnings("unused")
    private List<GlobalTransform> globalRecordTransforms;

    @Parameter(property = "recordValidation")
    @SuppressWarnings("unused")
    private RecordValidation recordValidation;

    @Parameter(property = "maxAllowedPageSize", defaultValue = "1000")
    @SuppressWarnings("unused")
    private int maxAllowedPageSize;

    @Parameter(property = "makeNodeStrategy", defaultValue = "false")
    @SuppressWarnings("unused")
    private boolean makeNodeStrategy;

    @Parameter(property = "useJdbcBatchingForDeletes", defaultValue = "true")
    @SuppressWarnings("unused")
    private boolean useJdbcBatchingForDeletes;

    @Parameter(property = "useJdbcBatchingForInserts", defaultValue = "true")
    @SuppressWarnings("unused")
    private boolean useJdbcBatchingForInserts;

    @Parameter(property = "experimental_requireTypeIdOnNode", defaultValue = "false")
    @SuppressWarnings("unused")
    private boolean experimental_requireTypeIdOnNode;

    @Parameter(property = "codeGenerationThresholds")
    @SuppressWarnings("unused")
    private CodeGenerationThresholds codeGenerationThresholds;

    @Parameter(property = "optionalSelect")
    @SuppressWarnings("unused")
    private OptionalSelect optionalSelect;

    // ============================================
    // Internal state for Generator interface
    // ============================================

    private Set<String> schemaFiles;

    @Override
    public void execute() throws MojoExecutionException {
        var transformOutputDir = project.getBasedir().toPath().resolve(TRANSFORM_TARGET_PATH);

        // 1. Collect schema files
        var inputSchemaFiles = SchemaTransformHelper.collectSchemaFiles(schemaRootDirectories);
        var descriptionSuffixForFeatures = SchemaTransformHelper.createDescriptionSuffixMap(schemaRootDirectories, descriptionSuffixFilename);

        try {
            Files.createDirectories(transformOutputDir);

            // 2. Transform and write generator-schema.graphql (for code generation)
            // Generator schema: NO feature flags, NO generator directive removal, NO federation removal
            var generatorConfig = new TransformConfig(inputSchemaFiles, directivesToRemove, descriptionSuffixForFeatures, false, false, expandConnections);
            var generatorSchema = new SchemaTransformer(generatorConfig).transformSchema();
            var generatorSchemaPath = transformOutputDir.resolve(GENERATOR_SCHEMA_FILENAME).toString();
            SchemaWriter.writeSchemaToDirectory(generatorSchema, GENERATOR_SCHEMA_FILENAME, transformOutputDir.toString(), false);

            // 3. Transform and write client-facing schemas (with feature flags if configured)
            var clientConfig = new TransformConfig(inputSchemaFiles, directivesToRemove, descriptionSuffixForFeatures, addFeatureFlags, removeGeneratorDirectives, expandConnections);
            var clientSchema = new SchemaTransformer(clientConfig).transformSchema();

            if (outputSchemas != null && !outputSchemas.isEmpty()) {
                // Separate schemas with flags from schemas without flags
                var schemasWithFlags = outputSchemas.stream()
                        .filter(o -> o.flags() != null && !o.flags().isEmpty())
                        .collect(Collectors.toSet());
                var schemasWithoutFlags = outputSchemas.stream()
                        .filter(o -> o.flags() == null || o.flags().isEmpty())
                        .collect(Collectors.toSet());

                // Write schemas WITHOUT flags directly (no splitFeatures needed)
                for (var schema : schemasWithoutFlags) {
                    var removeFed = schema.shouldRemoveFederationDefinitions(removeFederationDefinitions);
                    SchemaWriter.writeSchemaToDirectory(clientSchema, schema.fileName(), transformOutputDir.toString(), removeFed);
                }

                // Write schemas WITH flags using splitFeatures
                if (!schemasWithFlags.isEmpty()) {
                    var byFederationSetting = schemasWithFlags.stream()
                            .collect(Collectors.groupingBy(
                                    o -> o.shouldRemoveFederationDefinitions(removeFederationDefinitions),
                                    Collectors.toSet()));

                    for (var entry : byFederationSetting.entrySet()) {
                        var removeFed = entry.getKey();
                        var schemas = entry.getValue();
                        var reloadedSchema = SchemaTransformer.reloadSchema(clientSchema, removeFed);
                        var features = SchemaTransformer.splitFeatures(reloadedSchema, schemas);
                        for (var f : features) {
                            SchemaWriter.writeSchemaToDirectory(f.schema(), f.fileName(), transformOutputDir.toString(), removeFed);
                        }
                    }
                }
            } else {
                // Default: produce a single schema.graphql for clients
                SchemaWriter.writeSchemaToDirectory(clientSchema, "schema.graphql", transformOutputDir.toString(), removeFederationDefinitions);
            }

            // 4. Add transform output as resource
            var resource = new Resource();
            resource.setDirectory(transformOutputDir.toString());
            project.addResource(resource);

            // 5. Configure and run code generation
            this.schemaFiles = Set.of(generatorSchemaPath);
            GeneratorConfig.loadProperties(this);
            getGraphqlCodegenCustomTypeMapping();
            GraphQLGenerator.generate();

            // 6. Add generated sources
            project.addCompileSourceRoot(getOutputPath());

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private Map<String, String> getGraphqlCodegenCustomTypeMapping() {
        Set<Class<?>> userConfiguredScalarClasses = scalars == null ? Set.of() : scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        return ScalarUtils.initialize(userConfiguredScalarClasses).getScalarTypeNameMapping();
    }

    // ============================================
    // Generator interface implementation
    // ============================================

    @Override
    public String getOutputPath() {
        return outputPath;
    }

    @Override
    public String getOutputPackage() {
        return outputPackage;
    }

    @Override
    public String getApiPackageName() {
        return outputPackage + ".api";
    }

    @Override
    public String getModelPackageName() {
        return outputPackage + ".model";
    }

    @Override
    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    @Override
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            // Default to schema.graphql in transform output
            return Set.of("schema.graphql");
        }
        return userSchemaFiles;
    }

    @Override
    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    @Override
    public RecordValidation getRecordValidation() {
        return recordValidation;
    }

    @Override
    public int getMaxAllowedPageSize() {
        return maxAllowedPageSize;
    }

    @Override
    public List<? extends ExternalReference> getExternalReferences() {
        return externalReferences;
    }

    @Override
    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
    }

    @Override
    public boolean makeNodeStrategy() {
        return makeNodeStrategy;
    }

    @Override
    public boolean useJdbcBatchingForDeletes() {
        return useJdbcBatchingForDeletes;
    }

    @Override
    public boolean useJdbcBatchingForInserts() {
        return useJdbcBatchingForInserts;
    }

    @Override
    public CodeGenerationThresholds getCodeGenerationThresholds() {
        return codeGenerationThresholds;
    }

    @Override
    public boolean requireTypeIdOnNode() {
        return experimental_requireTypeIdOnNode;
    }

    @Override
    public Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }

    @Override
    public OptionalSelect getOptionalSelect() {
        return optionalSelect;
    }
}
