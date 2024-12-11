package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.Extension;
import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.configuration.RecordValidation;
import no.sikt.graphitron.configuration.externalreferences.ExternalMojoClassReference;
import no.sikt.graphitron.configuration.externalreferences.ExternalReference;
import no.sikt.graphitron.configuration.externalreferences.GlobalTransform;
import no.sikt.graphitron.mojo.Generator;
import no.sikt.graphitron.mojo.GraphQLGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.Set;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;

/**
 * Mojo for a single run of the code generation.
 */
@Mojo(
        name = "generate",
        defaultPhase = GENERATE_SOURCES
)
public class GenerateMojo extends AbstractMojo implements Generator {
    /**
     * The Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * The location where the code should be exported to.
     */
    @Parameter(property = "generate.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "generate.outputPackage", defaultValue = "no.sikt.graphql")
    private String outputPackage;

    /**
     * The comma-separated locations of the schema files to use for code generation.
     */
    @Parameter(property = "generate.schemaFiles", defaultValue = "${project.basedir}/target/generated-sources/schema.graphql", required = true)
    private Set<String> schemaFiles;

    /**
     * Package of previously generated schema code.
     */
    @Parameter(property = "generate.generatedSchemaCodePackage")
    private String generatedSchemaCodePackage;

    /**
     * The output folder for jOOQ generated code.
     */
    @Parameter(property = "generate.jooqGeneratedPackage")
    private String jooqGeneratedPackage;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "generate.externalReferences")
    @SuppressWarnings("unused")
    private List<ExternalMojoClassReference> externalReferences;

    /**
     * External reference elements that can be used in code generation.
     */
    @Parameter(property = "generate.externalReferenceImports")
    @SuppressWarnings("unused")
    private Set<String> externalReferenceImports;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "generate.globalRecordTransforms")
    @SuppressWarnings("unused")
    private List<GlobalTransform> globalRecordTransforms;

    @Parameter(property = "generate.extensions")
    @SuppressWarnings("unused")
    private List<Extension> extensions;

    @Parameter(property = "generate.recordValidation")
    private RecordValidation recordValidation;

    @Parameter(property = "generate.maxAllowedPageSize", defaultValue = "1000")
    private int maxAllowedPageSize;

    @Override
    public void execute() throws MojoExecutionException {
        GeneratorConfig.loadProperties(this);
        GraphQLGenerator.generate();
        project.addCompileSourceRoot(getOutputPath());
    }

    public String getOutputPath() {
        return outputPath;
    }

    public String getOutputPackage() {
        return outputPackage;
    }

    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    public String getGeneratedSchemaCodePackage() {
        return generatedSchemaCodePackage;
    }

    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    public RecordValidation getRecordValidation() {
        return recordValidation;
    }

    public int getMaxAllowedPageSize() {
        return maxAllowedPageSize;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public void setOutputPackage(String outputPackage) {
        this.outputPackage = outputPackage;
    }

    public void setSchemaFiles(Set<String> schemaFiles) {
        this.schemaFiles = schemaFiles;
    }

    public List<? extends ExternalReference> getExternalReferences() {
        return externalReferences;
    }

    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
    }

    public List<Extension> getExtensions() {
        return extensions;
    }

    public void setGeneratedSchemaCodePackage(String generatedSchemaCodePackage) {
        this.generatedSchemaCodePackage = generatedSchemaCodePackage;
    }

    public void setJooqGeneratedPackage(String jooqGeneratedPackage) {
        this.jooqGeneratedPackage = jooqGeneratedPackage;
    }

    public Set<String> getExternalReferenceImports() {
        return externalReferenceImports;
    }
}
