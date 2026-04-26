package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
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

    /**
     * Errors observed in the previous regeneration cycle, used by the formatter to emit a
     * {@code +N new, -M fixed, K unchanged} delta line. {@code null} means "no prior cycle yet"
     * and suppresses the delta on the first run; an empty set means "previous cycle was clean".
     */
    private Set<WatchErrorFormatter.DeltaKey> previousErrorKeys = null;

    @Override
    protected boolean packagesRequired() {
        return true;
    }

    @Override
    public void execute() throws MojoExecutionException {
        var initialCtx = buildContext();

        if (!skipInitial) {
            getLog().info(banner("initial run"));
            runOnce(initialCtx, "initial run");
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
     * generator, and registers any newly-discovered watch directories. The watch loop survives
     * both validation failures (rendered as a grouped tree) and infrastructure failures
     * (rendered as a stack trace); see {@link #runOnce}.
     */
    private void trigger() {
        try {
            var ctx = buildContext();
            getLog().info(banner("regenerate"));
            boolean ok = runOnce(ctx, "regenerate");
            if (ok) {
                for (Path root : resolveWatchRoots(ctx)) {
                    try {
                        watcher.addRoot(root);
                    } catch (IOException e) {
                        getLog().warn("graphitron:watch: failed to register new watch root " + root + ": " + e.getMessage());
                    }
                }
            }
        } catch (MojoExecutionException e) {
            getLog().error("graphitron:watch: failed to rebuild context", e);
        }
    }

    /**
     * Runs one generator pass and renders the outcome. {@link ValidationFailedException} is
     * caught and rendered via {@link WatchErrorFormatter} (no stack trace, since the structured
     * info is already in the tree); other {@link RuntimeException}s carry a stack trace because
     * they indicate an infrastructure or generator-side bug, not author input.
     *
     * @return {@code true} if the run completed without throwing
     */
    private boolean runOnce(RewriteContext ctx, String label) {
        try {
            new GraphQLRewriteGenerator(ctx).generate();
            previousErrorKeys = Set.of();
            getLog().info("graphitron:watch: " + label + " ok");
            return true;
        } catch (ValidationFailedException e) {
            String tree = WatchErrorFormatter.format(e.errors(), previousErrorKeys);
            previousErrorKeys = WatchErrorFormatter.keysOf(e.errors());
            getLog().error("graphitron:watch: " + label + " failed validation\n" + tree);
            return false;
        } catch (RuntimeException e) {
            // Reset delta tracking; an infra failure means the validator did not complete and a
            // delta computed against the prior validation cycle would be misleading.
            previousErrorKeys = null;
            getLog().error("graphitron:watch: " + label + " failed (infrastructure)", e);
            return false;
        }
    }

    private static String banner(String label) {
        return "── graphitron:watch: " + label + " ──";
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
