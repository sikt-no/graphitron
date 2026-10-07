package no.sikt.graphitron.rewrite.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Fills the fact store from the build's inputs and stops: the schema documents, the jOOQ catalog,
 * the classpath and the configuration, each read by the gatherer that owns it. No lint, no
 * validation, no generated sources, and no generator: the goal orchestrates against the model
 * alone. Invoke as {@code mvn graphitron:capture}.
 *
 * <p>It never fails over the schema it read, which is what it exists for. A store is how a
 * developer debugs a schema, explores it and writes the queries the rest of graphitron is made of,
 * and a broken schema is exactly when one is wanted; what the schema's own stages thought of the
 * document is in the store as rows, not in the build's exit code. The goal fails only where it
 * cannot capture.
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
        var ctx = runCapture();
        getLog().info("Captured graph '" + ctx.graphName() + "' into " + ctx.storeDirectory());
    }
}
