package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.OptionalSelect;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static no.sikt.graphitron.mojo.SchemaTransformRunner.DEFAULT_SCHEMA_FILENAME;
import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Mojo for GraphQL code generation.
 * <p>
 * If {@code <transform>} configuration is provided, schema transformation runs automatically
 * before code generation. Without transform configuration, the goal expects pre-transformed
 * schema files specified via {@code <schemaFiles>}.
 */
@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES)
public class GenerateMojo extends AbstractGraphitronMojo implements Generator {
    private static final String TRANSFORM_TARGET_PATH = "target/generated-resources/graphql_transformer/";

    /**
     * Optional transform configuration. If provided, schema transformation runs before code generation.
     */
    @Parameter
    private TransformConfiguration transform;

    /**
     * The directory where generated code should be written.
     */
    @Parameter(property = "graphitron.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    protected String outputPath;

    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "graphitron.outputPackage", defaultValue = "no.sikt.graphql")
    protected String outputPackage;

    /**
     * The comma-separated locations of the schema files to provide to the user.
     */
    @Parameter(property = "graphitron.userSchemaFiles")
    protected Set<String> userSchemaFiles;

    /**
     * Extra scalars that can be used in code generation. In addition to the default scalars provided by the graphql
     * Java and <a href="https://github.com/graphql-java/graphql-java-extended-scalars"> Extended Scalars</a> libraries
     */
    @Parameter(property = "graphitron.scalars")
    @SuppressWarnings("unused")
    protected List<ExternalMojoClassReference> scalars;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "graphitron.globalRecordTransforms")
    @SuppressWarnings("unused")
    protected List<GlobalTransform> globalRecordTransforms;

    @Parameter(property = "graphitron.recordValidation")
    @SuppressWarnings("unused")
    protected RecordValidation recordValidation;

    @Parameter(property = "graphitron.maxAllowedPageSize", defaultValue = "1000")
    @SuppressWarnings("unused")
    protected int maxAllowedPageSize;

    @Parameter(property = "graphitron.useJdbcBatchingForDeletes", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean useJdbcBatchingForDeletes;

    @Parameter(property = "graphitron.useJdbcBatchingForInserts", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean useJdbcBatchingForInserts;

    @Parameter(property = "graphitron.codeGenerationThresholds")
    @SuppressWarnings("unused")
    protected CodeGenerationThresholds codeGenerationThresholds;

    @Parameter(property = "generate.optionalSelect")
    @SuppressWarnings("unused")
    protected OptionalSelect optionalSelect;

    @Parameter(property = "generate.validateOverlappingInputFields", defaultValue = "true")
    @SuppressWarnings("unused")
    protected boolean validateOverlappingInputFields;

    @Override
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            return transform != null ? Set.of(DEFAULT_SCHEMA_FILENAME) : schemaFiles;
        }
        return userSchemaFiles;
    }

    @Override
    public void execute() throws MojoExecutionException {
        try {
            if (transform != null) {
                executeWithTransform();
            } else {
                executeWithoutTransform();
            }
        } catch (Exception e) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    private void executeWithTransform() throws MojoExecutionException, IOException {
        validateTransformConfiguration();

        var transformOutputDir = project.getBasedir().toPath().resolve(TRANSFORM_TARGET_PATH);

        // 1. Execute schema transformation
        var runner = new SchemaTransformRunner(transform);
        var result = runner.execute(transformOutputDir, true);

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
    }

    private void executeWithoutTransform() {
        GeneratorConfig.loadProperties(this);
        initializeScalars();
        GraphQLGenerator.generate();
        project.addCompileSourceRoot(getOutputPath());
    }

    private void validateTransformConfiguration() throws MojoExecutionException {
        if (transform.getSchemaRootDirectories() == null || transform.getSchemaRootDirectories().isEmpty()) {
            throw new MojoExecutionException("schemaRootDirectories is required in transform configuration.");
        }
    }

    private void initializeScalars() {
        Set<Class<?>> userConfiguredScalarClasses = scalars == null ? Set.of() : scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        ScalarUtils.initialize(userConfiguredScalarClasses);
    }

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
    public RecordValidation getRecordValidation() {
        return recordValidation;
    }

    @Override
    public int getMaxAllowedPageSize() {
        return maxAllowedPageSize;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public void setOutputPackage(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    @Override
    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
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
    public OptionalSelect getOptionalSelect() {
        return optionalSelect;
    }

    @Override
    public boolean validateOverlappingInputFields() {
        return validateOverlappingInputFields;
    }
}
