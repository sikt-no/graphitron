package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Watches the configured {@code <schemaInputs>} directories and re-runs the rewrite
 * generator on every {@code .graphqls} change. Blocks until interrupted (Ctrl+C). Invoke as
 * {@code mvn graphitron-rewrite:watch}.
 *
 * <p>The goal performs one full generator run on startup (unless
 * {@code -Dgraphitron.watch.skipInitial=true}) so the output tree is fresh when the loop
 * begins. Subsequent triggers are debounced ({@code -Dgraphitron.watch.debounceMs}, default
 * 300 ms). Validation failures and I/O errors are caught and logged; the watch loop resumes.
 */
@Mojo(
    name = "watch",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class WatchMojo extends AbstractRewriteMojo {

    @Parameter(property = "graphitron.watch.skipInitial", defaultValue = "false")
    boolean skipInitial;

    @Parameter(property = "graphitron.watch.debounceMs", defaultValue = "300")
    long debounceMs;

    private SchemaWatcher watcher;

    @Override
    protected boolean packagesRequired() {
        return true;
    }

    @Override
    public void execute() throws MojoExecutionException {
        var initialCtx = buildContext();

        if (!skipInitial) {
            try {
                new GraphQLRewriteGenerator(initialCtx).generate();
            } catch (RuntimeException e) {
                getLog().error("graphitron:watch: initial generator run failed", e);
            }
        }

        Set<Path> roots = resolveWatchRoots(initialCtx);
        if (roots.isEmpty()) {
            throw new MojoExecutionException(
                "graphitron:watch: no watch directories resolved from <schemaInputs>");
        }

        DebounceExecutor debounce = new DebounceExecutor(debounceMs);
        try {
            this.watcher = new SchemaWatcher(roots, debounce, this::trigger);
        } catch (IOException e) {
            debounce.close();
            throw new MojoExecutionException("graphitron:watch: failed to start watch service", e);
        }

        Thread shutdown = new Thread(() -> {
            watcher.close();
            debounce.close();
        }, "graphitron-watch-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        getLog().info("graphitron:watch: watching " + roots + "; Ctrl+C to stop");
        try {
            watcher.run();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
            watcher.close();
            debounce.close();
        }
    }

    /**
     * Re-expands {@code <schemaInputs>} (so newly-matched files are picked up), runs the
     * generator, and registers any newly-discovered watch directories. Catches every
     * {@link RuntimeException} so the watch loop survives validation and I/O failures; the
     * generator already log-surfaces validation errors via SLF4J before throwing.
     */
    private void trigger() {
        try {
            var ctx = buildContext();
            new GraphQLRewriteGenerator(ctx).generate();
            for (Path root : resolveWatchRoots(ctx)) {
                try {
                    watcher.addRoot(root);
                } catch (IOException e) {
                    getLog().warn("graphitron:watch: failed to register new watch root " + root + ": " + e.getMessage());
                }
            }
        } catch (MojoExecutionException | RuntimeException e) {
            getLog().error("graphitron:watch: regeneration failed: " + e.getMessage());
        }
    }

    private static Set<Path> resolveWatchRoots(RewriteContext ctx) {
        Set<Path> roots = new LinkedHashSet<>();
        for (var input : ctx.schemaInputs()) {
            Path file = Paths.get(input.sourceName());
            Path parent = file.getParent();
            if (parent != null) {
                roots.add(parent);
            }
        }
        return roots;
    }
}
