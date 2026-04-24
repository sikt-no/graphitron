package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Validates the GraphQL schema without writing any generated sources.
 * Runs schema loading, attribution, classification, and validation only.
 * Invoke as {@code mvn graphitron-rewrite:validate}.
 */
@Mojo(
    name = "validate",
    defaultPhase = LifecyclePhase.VALIDATE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class ValidateMojo extends AbstractRewriteMojo {

    @Override
    public void execute() throws MojoExecutionException {
        try {
            new GraphQLRewriteGenerator(buildContext()).validate();
        } catch (RuntimeException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info("Schema validation completed successfully");
    }
}
