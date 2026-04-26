package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.lsp.catalog.CompletionData;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.maven.dev.DevServer;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import no.sikt.graphitron.rewrite.maven.watch.SchemaWatcher;
import no.sikt.graphitron.rewrite.maven.watch.WatchErrorFormatter;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single user-facing entry point for editing graphitron schemas. Runs the
 * LSP server and the schema-input watch loop in one JVM, one terminal:
 *
 * <ul>
 *   <li>Binds {@code 127.0.0.1:8487} (override via
 *       {@code -Dgraphitron.dev.port=N}) and serves the LSP to whoever
 *       connects.</li>
 *   <li>Watches {@code <schemaInputs>} for {@code .graphqls} writes and
 *       re-runs the generator on every save (debounced).</li>
 *   <li>Watches the consumer's compiled jOOQ output for {@code .class}
 *       changes and rebuilds the in-process catalog atomically. Phase 2
 *       fills in the rebuild; today the wiring stands so a Phase 2 swap
 *       is mechanical.</li>
 * </ul>
 *
 * <p>Stop with Ctrl+C. See {@code getting-started.md}'s "Dev loop" for the
 * editor-side recipes.
 */
@Mojo(
    name = "dev",
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true
)
public class DevMojo extends AbstractRewriteMojo {

    static final int DEFAULT_PORT = 8487;
    static final String LOOPBACK_HOST = "127.0.0.1";

    @Parameter(property = "graphitron.dev.port", defaultValue = "8487")
    int port;

    @Parameter(property = "graphitron.dev.debounceMs", defaultValue = "300")
    long debounceMs;

    @Parameter(property = "graphitron.dev.skipInitial", defaultValue = "false")
    boolean skipInitial;

    private SchemaWatcher schemaWatcher;
    private SchemaWatcher classpathWatcher;
    private DebounceExecutor schemaDebounce;
    private DebounceExecutor classpathDebounce;
    private DevServer server;
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
            runGeneratorPass(initialCtx, "initial run");
        }

        var workspace = new Workspace(CompletionData.empty());
        bindServer(workspace);
        Set<Path> schemaRoots = startSchemaWatcher(initialCtx, workspace);
        startClasspathWatcher(initialCtx, workspace);

        Thread shutdown = new Thread(this::cleanup, "graphitron-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        getLog().info("graphitron-rewrite:dev: LSP listening on " + LOOPBACK_HOST + ":" + server.port()
            + "; watching " + schemaRoots + "; Ctrl+C to stop");
        try {
            schemaWatcher.run();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // shutdown already in progress
            }
            cleanup();
        }
    }

    private void bindServer(Workspace workspace) throws MojoExecutionException {
        try {
            this.server = new DevServer(new InetSocketAddress(LOOPBACK_HOST, port), workspace);
        } catch (BindException e) {
            throw new MojoExecutionException(
                "graphitron-rewrite:dev: port " + port + " is already in use. "
                    + "Pass -Dgraphitron.dev.port=N to pick a different port.", e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                "graphitron-rewrite:dev: failed to bind " + LOOPBACK_HOST + ":" + port, e);
        }
    }

    private Set<Path> startSchemaWatcher(RewriteContext ctx, Workspace workspace) throws MojoExecutionException {
        Set<Path> roots = resolveSchemaRoots(ctx);
        if (roots.isEmpty()) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron-rewrite:dev: no watch directories resolved from <schemaInputs>");
        }
        this.schemaDebounce = new DebounceExecutor(debounceMs);
        try {
            this.schemaWatcher = new SchemaWatcher(roots, schemaDebounce, () -> regenerate(workspace));
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron-rewrite:dev: failed to start schema watcher", e);
        }
        return roots;
    }

    private void startClasspathWatcher(RewriteContext ctx, Workspace workspace) throws MojoExecutionException {
        Set<Path> roots = resolveClasspathRoots(ctx);
        if (roots.isEmpty()) {
            getLog().info("graphitron-rewrite:dev: skipping classpath watcher; "
                + "no compiled jOOQ output yet at " + ctx.basedir().resolve("target/classes"));
            return;
        }
        this.classpathDebounce = new DebounceExecutor(debounceMs);
        try {
            this.classpathWatcher = new SchemaWatcher(
                roots, classpathDebounce, () -> rebuildCatalog(workspace), ".class");
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron-rewrite:dev: failed to start classpath watcher", e);
        }
        Thread classpathThread = new Thread(classpathWatcher::run, "graphitron-dev-classpath");
        classpathThread.setDaemon(true);
        classpathThread.start();
    }

    private void regenerate(Workspace workspace) {
        try {
            var ctx = buildContext();
            getLog().info(banner("regenerate"));
            runGeneratorPass(ctx, "regenerate");
            for (Path root : resolveSchemaRoots(ctx)) {
                try {
                    schemaWatcher.addRoot(root);
                } catch (IOException e) {
                    getLog().warn("graphitron-rewrite:dev: failed to register new watch root "
                        + root + ": " + e.getMessage());
                }
            }
        } catch (MojoExecutionException e) {
            getLog().error("graphitron-rewrite:dev: failed to rebuild context", e);
        }
        // Notify the LSP that generated sources changed; Phase 3 turns
        // this into a real diagnostic refresh, slice 2 just records the
        // intent so open files end up in the recalc queue.
        workspace.markAllForRecalculation();
    }

    private void rebuildCatalog(Workspace workspace) {
        // Phase 2 deliverable: rebuild the catalog from the consumer's
        // compiled jOOQ classes via RewriteCatalogView. Slice 2 wires
        // the trigger so Phase 2 only has to fill in the builder.
        getLog().info("graphitron-rewrite:dev: classpath change detected; catalog rebuild placeholder (Phase 2)");
        workspace.setCatalog(CompletionData.empty());
    }

    private boolean runGeneratorPass(RewriteContext ctx, String label) {
        try {
            new GraphQLRewriteGenerator(ctx).generate();
            previousErrorKeys = Set.of();
            getLog().info("graphitron-rewrite:dev: " + label + " ok");
            return true;
        } catch (ValidationFailedException e) {
            String tree = WatchErrorFormatter.format(e.errors(), previousErrorKeys);
            previousErrorKeys = WatchErrorFormatter.keysOf(e.errors());
            getLog().error("graphitron-rewrite:dev: " + label + " failed validation\n" + tree);
            return false;
        } catch (RuntimeException e) {
            previousErrorKeys = null;
            getLog().error("graphitron-rewrite:dev: " + label + " failed (infrastructure)", e);
            return false;
        }
    }

    private void cleanup() {
        if (schemaWatcher != null) schemaWatcher.close();
        if (classpathWatcher != null) classpathWatcher.close();
        if (schemaDebounce != null) schemaDebounce.close();
        if (classpathDebounce != null) classpathDebounce.close();
        if (server != null) server.close();
    }

    private static String banner(String label) {
        return "── graphitron-rewrite:dev: " + label + " ──";
    }

    private static Set<Path> resolveSchemaRoots(RewriteContext ctx) {
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

    private static Set<Path> resolveClasspathRoots(RewriteContext ctx) {
        // jOOQ packages are dotted; the on-disk layout uses path separators.
        Path classes = ctx.basedir().resolve("target/classes");
        Path target = classes.resolve(ctx.jooqPackage().replace('.', '/'));
        if (!java.nio.file.Files.isDirectory(target)) {
            return Set.of();
        }
        return Set.of(target);
    }
}
