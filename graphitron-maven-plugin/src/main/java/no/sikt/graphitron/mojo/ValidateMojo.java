package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generate.Validator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.util.Set;

/**
 * Mojo for validating GraphQL schemas without generating code.
 * This is significantly faster than the generate goal and provides
 * quick feedback on schema correctness.
 * <p>
 * The {@code @Execute} annotation ensures the schema transformation runs before validation,
 * so {@code mvn graphitron:validate} works even after {@code mvn clean}.
 */
@Mojo(name = "validate", requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.GENERATE_RESOURCES)
public class ValidateMojo extends AbstractMojo implements Validator {

    /**
     * The comma-separated locations of the schema files to validate.
     */
    @Parameter(property = "validate.schemaFiles", defaultValue = "${project.basedir}/target/generated-resources/schema.graphql", required = true)
    private Set<String> schemaFiles;

    /**
     * The package where jOOQ generated code resides.
     * Required for validating database references.
     */
    @Parameter(property = "validate.jooqGeneratedPackage", required = true)
    private String jooqGeneratedPackage;

    /**
     * Whether to enable Node strategy validation.
     */
    @Parameter(property = "validate.makeNodeStrategy", defaultValue = "false")
    private boolean makeNodeStrategy;

    /**
     * Whether type ID is required on Node types.
     */
    @Parameter(property = "validate.requireTypeIdOnNode", defaultValue = "false")
    private boolean requireTypeIdOnNode;

    @Override
    public void execute() throws MojoExecutionException {
        try {
            ValidationHandler.resetErrorMessages();
            ValidationHandler.resetWarningMessages();
            GeneratorConfig.loadValidatorProperties(this);
            var schema = GraphQLGenerator.getProcessedSchema(true);
            schema.validate();
            getLog().info("Schema validation completed successfully");
        } catch (Exception e) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + e.getMessage(), e);
        }
    }

    @Override
    public Set<String> getSchemaFiles() {
        return schemaFiles;
    }

    @Override
    public String getJooqGeneratedPackage() {
        return jooqGeneratedPackage;
    }

    @Override
    public boolean makeNodeStrategy() {
        return makeNodeStrategy;
    }

    @Override
    public boolean requireTypeIdOnNode() {
        return requireTypeIdOnNode;
    }
}
