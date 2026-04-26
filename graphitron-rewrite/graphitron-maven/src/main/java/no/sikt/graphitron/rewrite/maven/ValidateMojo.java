package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Validates the GraphQL schema without writing any generated sources.
 * Runs schema loading, attribution, classification, and validation only.
 * Invoke as {@code mvn graphitron:validate}. The outputPackage
 * and jooqPackage parameters are optional for this goal; a sentinel is
 * substituted so the classifier stage still type-checks without emitting code.
 */
@Mojo(
    name = "validate",
    defaultPhase = LifecyclePhase.VALIDATE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class ValidateMojo extends AbstractRewriteMojo {

    @Override
    protected boolean packagesRequired() {
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException {
        runGenerator(GraphQLRewriteGenerator::validate);
        getLog().info("Schema validation completed successfully");
    }
}
