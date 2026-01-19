package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.CodeGenerationThresholds;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.definitions.helpers.ScalarUtils;
import no.sikt.graphitron.generate.Generator;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Mojo for a single run of the code generation.
 */
@Mojo(name = "generate", defaultPhase = GENERATE_SOURCES)
public class GenerateMojo extends AbstractGraphitronMojo implements Generator {
    /**
     * The Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * The location where the code should be exported to.
     */
    @Parameter(property = "graphitron.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "graphitron.outputPackage", defaultValue = "no.sikt.graphql")
    private String outputPackage;

    /**
     * The comma-separated locations of the schema files to provide to the user.
     */
    @Parameter(property = "graphitron.userSchemaFiles")
    private Set<String> userSchemaFiles;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "graphitron.externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;

    /**
     * Extra scalars that can be used in code generation. In addition to the default scalars provided by the graphql
     * Java and <a href="https://github.com/graphql-java/graphql-java-extended-scalars"> Extended Scalars</a> libraries
     */
    @Parameter(property = "graphitron.scalars")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> scalars;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "graphitron.externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "graphitron.globalRecordTransforms")
    @SuppressWarnings("unused")
    private List<GlobalTransform> globalRecordTransforms;

    @Parameter(property = "graphitron.recordValidation")
    @SuppressWarnings("unused")
    private RecordValidation recordValidation;

    @Parameter(property = "graphitron.maxAllowedPageSize", defaultValue = "1000")
    @SuppressWarnings("unused")
    private int maxAllowedPageSize;

    @Parameter(property = "graphitron.useJdbcBatchingForDeletes", defaultValue = "true")
    @SuppressWarnings("unused")
    private boolean useJdbcBatchingForDeletes;

    @Parameter(property = "graphitron.useJdbcBatchingForInserts", defaultValue = "true")
    @SuppressWarnings("unused")
    private boolean useJdbcBatchingForInserts;

    @Parameter(property = "graphitron.codeGenerationThresholds")
    @SuppressWarnings("unused")
    private CodeGenerationThresholds codeGenerationThresholds;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            GeneratorConfig.loadProperties(this);
            getGraphqlCodegenCustomTypeMapping();
            GraphQLGenerator.generate();
            project.addCompileSourceRoot(getOutputPath());
        } catch (Exception e) {
            // Log warnings for visibility before throwing
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    private Map<String, String> getGraphqlCodegenCustomTypeMapping() {
        Set<Class<?>> userConfiguredScalarClasses = scalars.stream()
                .map(ExternalMojoClassReference::classReference)
                .collect(Collectors.toSet());
        return ScalarUtils.initialize(userConfiguredScalarClasses).getScalarTypeNameMapping();
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
    public Set<String> getUserSchemaFiles() {
        if (userSchemaFiles == null || userSchemaFiles.isEmpty()) {
            return schemaFiles;
        }
        return userSchemaFiles;
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
    public List<? extends ExternalReference> getExternalReferences() {
        return externalReferences;
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
    public Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }
}
