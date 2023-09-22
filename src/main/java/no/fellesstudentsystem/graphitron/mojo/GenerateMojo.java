package no.fellesstudentsystem.graphitron.mojo;

import static org.apache.maven.plugins.annotations.LifecyclePhase.GENERATE_SOURCES;
import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.util.List;
import java.util.Set;

/**
 * Mojo for a single run of the code generation.
 */
@Mojo(
        name = "generate",
        defaultPhase = GENERATE_SOURCES
)
public class GenerateMojo extends AbstractMojo {
    /**
     * The Maven project.
     */
    @Parameter(property = "project", required = true, readonly = true)
    private MavenProject project;

    /**
     * Package root for this project. This is temporary and should be replaced once Graphitron is unlinked from FS.
     */
    @Parameter(property = "generate.topPackage", defaultValue = "no.fellesstudentsystem", required = true)
    private String topPackage;

    /**
     * The location where the code should be exported to.
     */
    @Parameter(property = "generate.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The package where the code should be exported to.
     */
    @Parameter(property = "generate.outputPackage", defaultValue = "no.fellesstudentsystem.graphql")
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
     * External enums that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalEnums")
    @SuppressWarnings("unused")
    private String externalEnums;

    /**
     * External conditions that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalConditions")
    @SuppressWarnings("unused")
    private String externalConditions;

    /**
     * External services that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalServices")
    @SuppressWarnings("unused")
    private String externalServices;

    /**
     * External exceptions that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalExceptions")
    @SuppressWarnings("unused")
    private String externalExceptions;

    /**
     * External exceptions that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalTransforms")
    @SuppressWarnings("unused")
    private String externalTransforms;

    /**
     * Transforms that apply to all records, or a subset of records.
     */
    @Parameter(property = "generate.globalRecordTransforms")
    @SuppressWarnings("unused")
    private List<GlobalTransform> globalRecordTransforms;

    /**
     * Indicates whether generated mutations should include validation of JOOQ records
     * through the Jakarta Bean Validation specification.
     */
    @Parameter(property = "generate.shouldGenerateRecordValidation", defaultValue="true")
    @SuppressWarnings("unused")
    private boolean shouldGenerateRecordValidation;

    @Override
    public void execute() throws MojoExecutionException {
        GeneratorConfig.loadProperties(this);
        GraphQLGenerator.generate();

        project.addCompileSourceRoot(getOutputPath());
    }

    public String getTopPackage() {
        return topPackage;
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

    public String getExternalEnums() {
        return externalEnums;
    }

    public String getExternalConditions() {
        return externalConditions;
    }

    public String getExternalServices() {
        return externalServices;
    }

    public String getExternalExceptions() {
        return externalExceptions;
    }

    public String getExternalTransforms() {
        return externalTransforms;
    }

    public boolean shouldGenerateRecordValidation() {
        return shouldGenerateRecordValidation;
    }

    public List<GlobalTransform> getGlobalTransforms() {
        return globalRecordTransforms;
    }

    public void setTopPackage(String topPackage) {
        this.topPackage = topPackage;
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

    public void setGeneratedSchemaCodePackage(String generatedSchemaCodePackage) {
        this.generatedSchemaCodePackage = generatedSchemaCodePackage;
    }

    public void setJooqGeneratedPackage(String jooqGeneratedPackage) {
        this.jooqGeneratedPackage = jooqGeneratedPackage;
    }
}
