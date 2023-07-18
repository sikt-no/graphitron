package no.fellesstudentsystem.graphitron;

import no.fellesstudentsystem.graphitron.configuration.GeneratorConfig;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

@Mojo(name = "generate")
public class GenerateMojo extends AbstractMojo {
    /**
     * The location where the code should be exported to.
     */
    @Parameter(property = "generate.outputPath", defaultValue = "${project.build.directory}/generated-sources")
    private String outputPath;

    /**
     * The comma-separated locations of the schema files to use for code generation.
     */
    @Parameter(property = "generate.schemaFiles", defaultValue = "${project.basedir}/target/generated-sources/schema.graphql", required = true)
    private List<String> schemaFiles;

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
    private String externalEnums;

    /**
     * External conditions that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalConditions")
    private String externalConditions;

    /**
     * External services that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalServices")
    private String externalServices;

    /**
     * External exceptions that can be referenced in code generation.
     */
    @Parameter(property = "generate.externalExceptions")
    private String externalExceptions;

    @Override
    public void execute() throws MojoExecutionException {
        GeneratorConfig.loadProperties(this);
        GraphQLGenerator.generate();
    }

    public String getOutputPath() {
        return outputPath;
    }

    public List<String> getSchemaFiles() {
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
}
