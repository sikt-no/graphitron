package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generate.GraphQLGenerator;
import no.sikt.graphitron.generate.Validator;
import no.sikt.graphitron.rewrite.GraphitronSchemaBuilder;
import no.sikt.graphitron.rewrite.GraphitronSchemaValidator;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.validation.ValidationHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.util.List;
import java.util.stream.Collectors;

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

    /**
     * When {@code true} (default), any {@link ValidationError} produced by the rewrite
     * validator fails the build. Set to {@code false} (via pom {@code <configuration>}
     * or {@code -Dgraphitron.failOnRewriteValidationError=false}) to downgrade these
     * errors back to warnings — an escape hatch for in-progress migrations.
     *
     * <p><b>Cost of disabling:</b> re-opens the runtime-{@code UnsupportedOperationException}
     * window that this validation exists to close. Schemas referencing a stubbed variant will
     * pass validation, generate, and throw at the first request hitting that variant.
     * Intended as a temporary setting, not a long-term one.
     */
    @Parameter(property = "graphitron.failOnRewriteValidationError", defaultValue = "true")
    private boolean failOnRewriteValidationError;

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

        // Rewrite pipeline — collect errors, log each as a warning. Any throw must happen
        // OUTSIDE this try/catch so it escapes the pipeline-crash swallow.
        List<ValidationError> rewriteErrors = List.of();
        try {
            var registry = getTypeDefinitionRegistry(GeneratorConfig.generatorSchemaFiles());
            var graphitronSchema = GraphitronSchemaBuilder.build(registry);
            rewriteErrors = new GraphitronSchemaValidator().validate(graphitronSchema);
            for (var error : rewriteErrors) {
                getLog().warn(formatError(error));
            }
        } catch (Exception e) {
            getLog().debug("New pipeline validation skipped: " + e.getMessage());
        }

        // Legacy failure takes priority — preserves today's behaviour when both pipelines fail.
        if (legacyFailure != null) {
            ValidationHandler.logWarnings();
            throw new MojoExecutionException("\n" + legacyFailure.getMessage(), legacyFailure);
        }

        // Rewrite fail branch — outside the rewrite try/catch so the throw escapes.
        if (!rewriteErrors.isEmpty() && failOnRewriteValidationError) {
            String body = rewriteErrors.stream()
                .map(ValidateMojo::formatError)
                .map(line -> "  " + line)
                .collect(Collectors.joining("\n"));
            throw new MojoExecutionException(
                "\nRewrite validation found " + rewriteErrors.size() + " error(s):\n" + body
                + "\n\nSet -Dgraphitron.failOnRewriteValidationError=false to downgrade to warnings "
                + "— note this re-opens the runtime UnsupportedOperationException window this check exists to close.");
        }

        getLog().info("Schema validation completed successfully");
    }

    private static String formatError(ValidationError error) {
        var loc = error.location();
        if (loc != null) {
            return loc.getSourceName() + ":" + loc.getLine() + ":" + loc.getColumn() + ": " + error.message();
        }
        return error.message();
    }
}