package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generate.Validator;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import static no.sikt.graphql.schema.SchemaReadingHelper.getTypeDefinitionRegistry;

/**
 * Mojo for validating GraphQL schemas without generating code.
 * This is significantly faster than the generate goal and provides
 * quick feedback on schema correctness.
 * <p>
 * The {@code @Execute} annotation ensures the schema transformation runs before validation,
 * so {@code mvn graphitron:validate} works even after {@code mvn clean}.
 */
@Mojo(name = "validate", defaultPhase = LifecyclePhase.VALIDATE, requiresDependencyResolution = ResolutionScope.COMPILE)
@Execute(phase = LifecyclePhase.GENERATE_RESOURCES)
public class ValidateMojo extends AbstractGraphitronMojo implements Validator {

    @Override
    public void execute() throws MojoExecutionException {
        ValidationHandler.resetErrorMessages();
        ValidationHandler.resetWarningMessages();
        GeneratorConfig.loadValidatorProperties(this);

        // Legacy pipeline — errors fail the build (unchanged behaviour)
        Exception legacyFailure = null;
        try {
            GraphQLGenerator.getProcessedSchema(true).validate();
        } catch (Exception e) {
            legacyFailure = e;
        }

        // New pipeline — issues reported as warnings (non-blocking during migration)
        try {
            var registry = getTypeDefinitionRegistry(GeneratorConfig.generatorSchemaFiles());
            var graphitronSchema = GraphitronSchemaBuilder.build(registry);
            var errors = new GraphitronSchemaValidator().validate(graphitronSchema);
            for (var error : errors) {
                var loc = error.location();
                if (loc != null) {
                    getLog().warn(loc.getSourceName() + ":" + loc.getLine() + ":" + loc.getColumn()
                        + ": " + error.message());
                } else {
                    getLog().warn(error.message());
                }
            }
            if (!errors.isEmpty()) {
                getLog().warn("New pipeline found " + errors.size() + " issue(s) — treated as warnings during migration");
            }
        } catch (Exception e) {
            getLog().debug("New pipeline validation skipped: " + e.getMessage());
        }

        // Fail if legacy pipeline errored; new-pipeline warnings already logged above
        if (legacyFailure != null) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + legacyFailure.getMessage(), legacyFailure);
        }

        getLog().info("Schema validation completed successfully");
    }
}