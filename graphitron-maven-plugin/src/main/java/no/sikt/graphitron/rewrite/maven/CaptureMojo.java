package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Fills the fact store from the GraphQL schema and stops. Reads the schema inputs, classifies
 * them, loads the jOOQ catalog, transcribes the graph's facts, and does nothing else: no lint, no
 * validation, no generated sources. Invoke as {@code mvn graphitron:capture}.
 *
 * <p>Unlike {@code graphitron:validate}, this goal never fails over the schema it read. That is
 * the reason it exists: {@code validate} also fills a store, but on its way to failing the build,
 * so the one command that produced a store refused to produce one exactly when a reader most wants
 * to ask what is wrong. A command whose job is to produce something should not refuse because it
 * disliked the input. A schema too broken to classify still fails, since there is then no graph to
 * transcribe.
 *
 * <p>The {@code outputPackage} and {@code jooqPackage} parameters are optional, as they are for
 * {@code validate}, so the goal runs from the command line without a configured execution block.
 * A run that falls back to the sentinel for {@code jooqPackage} warns, because the catalog it then
 * loads is empty: the store gets its graph and no database facts, which is a poor store to hand a
 * reader.
 */
@Mojo(
    name = "capture",
    defaultPhase = LifecyclePhase.VALIDATE,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class CaptureMojo extends AbstractRewriteMojo {

    @Override
    protected boolean packagesRequired() {
        return false;
    }

    @Override
    public void execute() throws MojoExecutionException {
        if (jooqPackage == null || jooqPackage.isBlank()) {
            getLog().warn("<jooqPackage> is not configured, so this run captures no database "
                + "facts: the store will hold the graph with no tables, columns or keys behind "
                + "it. Configure <jooqPackage> to capture the catalog too.");
        }
        var ctx = runGenerator(GraphQLRewriteGenerator::capture);
        getLog().info("Captured graph '" + ctx.graphName() + "' into " + ctx.storeDirectory());
    }
}
