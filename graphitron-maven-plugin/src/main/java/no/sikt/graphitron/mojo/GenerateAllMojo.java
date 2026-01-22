package no.sikt.graphitron.mojo;

import no.fellesstudentsystem.schema_transformer.OutputSchema;
import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.OptionalSelect;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generate.Validator;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Combined Mojo that performs schema transformation and code generation in a single execution.
 * This simplifies the setup for new subgraphs by eliminating the need for separate transform and generate executions.
 */
@Mojo(name = "generate-all", defaultPhase = GENERATE_SOURCES)
public class GenerateAllMojo extends AbstractGenerateMojo  {
    private static final String TRANSFORM_TARGET_PATH = "target/generated-resources/graphql_transformer/";

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


    @Override
    public void execute() throws MojoExecutionException {
        var transformOutputDir = project.getBasedir().toPath().resolve(TRANSFORM_TARGET_PATH);

        try {
            // 1. Execute schema transformation
            var executorConfig = new GenerateAllExecutor.Config(
                    schemaRootDirectories,
                    descriptionSuffixFilename,
                    removeGeneratorDirectives,
                    removeFederationDefinitions,
                    expandConnections,
                    addFeatureFlags,
                    outputSchemas,
                    directivesToRemove
            );
            var executor = new GenerateAllExecutor(executorConfig);
            var result = executor.execute(transformOutputDir);

            // 2. Add transform output as resource
            var resource = new Resource();
            resource.setDirectory(transformOutputDir.toString());
            project.addResource(resource);

            // 3. Configure and run code generation
            this.schemaFiles = Set.of(result.generatorSchemaPath());
            GeneratorConfig.loadProperties(this);
            initializeScalars();
            GraphQLGenerator.generate();

            // 4. Add generated sources
            project.addCompileSourceRoot(getOutputPath());

        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }

    private void initializeScalars() {
        Set<Class<?>> userConfiguredScalarClasses = scalars == null ? Set.of() : scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        ScalarUtils.initialize(userConfiguredScalarClasses);
    }
}
