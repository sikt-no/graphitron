package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Runs the rewrite code-generation pipeline and writes generated Java sources.
 * Invoke as {@code mvn graphitron-rewrite:generate}.
 */
@Mojo(
    name = "generate",
    defaultPhase = LifecyclePhase.GENERATE_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class GenerateMojo extends AbstractRewriteMojo {

    @Override
    protected boolean packagesRequired() {
        return true;
    }

    @Override
    public void execute() throws MojoExecutionException {
        runGenerator(GraphQLRewriteGenerator::generate);
        project.addCompileSourceRoot(outputDirectory);
    }
}
