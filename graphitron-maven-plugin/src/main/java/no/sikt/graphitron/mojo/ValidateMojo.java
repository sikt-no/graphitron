package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generate.Validator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Mojo for validating GraphQL schemas without generating code.
 * This is significantly faster than the generate goal and provides
 * quick feedback on schema correctness.
 * <p>
 * The {@code @Execute} annotation ensures the schema transformation runs before validation,
 * so {@code mvn graphitron:validate} works even after {@code mvn clean}.
 * <p>
 * Configuration is inherited from {@link AbstractGraphitronMojo}, sharing the same
 * parameters as {@link GenerateMojo} (schemaFiles, jooqGeneratedPackage, etc.).
 */
@Mojo(name = "validate", requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.GENERATE_RESOURCES)
public class ValidateMojo extends AbstractGraphitronMojo implements Validator {

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
}
