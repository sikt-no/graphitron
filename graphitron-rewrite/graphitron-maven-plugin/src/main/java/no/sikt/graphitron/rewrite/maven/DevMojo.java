package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
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
import java.util.concurrent.atomic.AtomicReference;

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
 *   <li>Watches every reactor project's {@code target/classes} for
 *       {@code .class} changes and rebuilds the in-process catalog
 *       atomically. Both jOOQ output (tables / columns / FKs) and
 *       consumer service / condition / record classes — whether declared
 *       in the schema module or a sibling reactor module — flow through
 *       the same rebuild trigger.</li>
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
        // Initial codegen and LSP catalog build both reflect on consumer classes, so they share
        // one URLClassLoader scope. Watchers that follow only resolve paths (no reflection); they
        // capture the ctx returned here, whose loader is *closed* by the time setup proceeds.
        //
        // LOAD-BEARING: code added below that uses `initialCtx` must read only path-shaped fields
        // (`schemaInputs`, `basedir`, `classpathRoots`). Calling `initialCtx.codegenLoader()` here
        // returns a closed URLClassLoader and would surface as a confusing ClassNotFoundException
        // on the next reflection attempt. Each file-change callback (regenerate / rebuildCatalog)
        // opens its own scope and is the right place to reach for a live loader.
        var initialCtxHolder = new AtomicReference<RewriteContext>();
        var initialHolder = new AtomicReference<InitialOutput>();
        withCodegenScope(ctx -> {
            initialCtxHolder.set(ctx);
            if (!skipInitial) {
                getLog().info(banner("initial run"));
                runGeneratorPass(ctx, "initial run");
            }
            initialHolder.set(buildOutputQuietly(ctx));
        });
        var initialCtx = initialCtxHolder.get();
        var initial = initialHolder.get();

        var workspace = new Workspace(initial.catalog(), LspVocabulary.load());
        if (initial.snapshot() instanceof LspSchemaSnapshot.Built built) {
            workspace.setCatalogAndSnapshot(initial.catalog(), built);
        }
        bindServer(workspace);
        Set<Path> schemaRoots = startSchemaWatcher(initialCtx, workspace);
        startClasspathWatcher(initialCtx, workspace);

        Thread shutdown = new Thread(this::cleanup, "graphitron-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        getLog().info("graphitron:dev: LSP listening on " + LOOPBACK_HOST + ":" + server.port()
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
                "graphitron:dev: port " + port + " is already in use. "
                    + "Pass -Dgraphitron.dev.port=N to pick a different port.", e);
        } catch (IOException e) {
            throw new MojoExecutionException(
                "graphitron:dev: failed to bind " + LOOPBACK_HOST + ":" + port, e);
        }
    }

    private Set<Path> startSchemaWatcher(RewriteContext ctx, Workspace workspace) throws MojoExecutionException {
        Set<Path> roots = resolveSchemaRoots(ctx);
        if (roots.isEmpty()) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron:dev: no watch directories resolved from <schemaInputs>");
        }
        this.schemaDebounce = new DebounceExecutor(debounceMs);
        try {
            this.schemaWatcher = new SchemaWatcher(roots, schemaDebounce, () -> regenerate(workspace));
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron:dev: failed to start schema watcher", e);
        }
        return roots;
    }

    private void startClasspathWatcher(RewriteContext ctx, Workspace workspace) throws MojoExecutionException {
        Set<Path> roots = resolveClasspathRoots(ctx);
        if (roots.isEmpty()) {
            getLog().info("graphitron:dev: skipping classpath watcher; "
                + "no compiled output yet under any reactor project's target/classes");
            return;
        }
        this.classpathDebounce = new DebounceExecutor(debounceMs);
        try {
            this.classpathWatcher = new SchemaWatcher(
                roots, classpathDebounce, () -> rebuildCatalog(workspace), ".class");
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron:dev: failed to start classpath watcher", e);
        }
        Thread classpathThread = new Thread(classpathWatcher::run, "graphitron-dev-classpath");
        classpathThread.setDaemon(true);
        classpathThread.start();
    }

    private void regenerate(Workspace workspace) {
        try {
            withCodegenScope(ctx -> {
                getLog().info(banner("regenerate"));
                runGeneratorPass(ctx, "regenerate");
                for (Path root : resolveSchemaRoots(ctx)) {
                    try {
                        schemaWatcher.addRoot(root);
                    } catch (IOException e) {
                        getLog().warn("graphitron:dev: failed to register new watch root "
                            + root + ": " + e.getMessage());
                    }
                }
                // The schema may have changed which scalars are declared
                // or which directives the user has authored, so refresh
                // both projections (catalog + snapshot) from the freshly
                // parsed bundle, atomically. On parse failure, demote the
                // snapshot to Built.Previous so consumers see "stale"
                // rather than punishing the user for the broken parse.
                try {
                    var output = new GraphQLRewriteGenerator(ctx).buildOutput();
                    workspace.setCatalogAndSnapshot(output.catalog(), output.snapshot());
                } catch (RuntimeException e) {
                    getLog().warn("graphitron:dev: catalog refresh after save failed; "
                        + "keeping previous: " + e.getMessage());
                    workspace.demoteSnapshot();
                    workspace.markAllForRecalculation();
                }
            });
        } catch (MojoExecutionException e) {
            getLog().error("graphitron:dev: failed to rebuild context", e);
            workspace.demoteSnapshot();
            workspace.markAllForRecalculation();
        }
    }

    private void rebuildCatalog(Workspace workspace) {
        getLog().info("graphitron:dev: classpath change detected; rebuilding catalog");
        try {
            withCodegenScope(ctx -> {
                try {
                    var catalog = new GraphQLRewriteGenerator(ctx).buildCatalog();
                    workspace.setCatalog(catalog);
                    getLog().info("graphitron:dev: catalog refreshed (" + catalog.tables().size()
                        + " tables, " + catalog.types().size() + " scalars)");
                } catch (RuntimeException e) {
                    // Bad schema mid-edit: keep the previous catalog so completions
                    // do not silently disappear. The next save will re-trigger.
                    getLog().warn("graphitron:dev: catalog rebuild failed; keeping previous: "
                        + e.getMessage());
                }
            });
        } catch (MojoExecutionException e) {
            getLog().error("graphitron:dev: catalog rebuild failed (context)", e);
        }
    }

    /**
     * Initial catalog + snapshot pair at dev-goal startup. A schema parse /
     * classification failure is surfaced as a warning and an empty catalog
     * plus {@link LspSchemaSnapshot.Unavailable}: the LSP must still come
     * up so the developer can fix the schema, and the schema watcher will
     * re-build on the next save.
     */
    private InitialOutput buildOutputQuietly(RewriteContext ctx) {
        try {
            var output = new GraphQLRewriteGenerator(ctx).buildOutput();
            return new InitialOutput(output.catalog(), output.snapshot());
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: initial catalog build failed; "
                + "starting with empty catalog: " + e.getMessage());
            return new InitialOutput(CompletionData.empty(), LspSchemaSnapshot.unavailable());
        }
    }

    private record InitialOutput(CompletionData catalog, LspSchemaSnapshot snapshot) {}

    private boolean runGeneratorPass(RewriteContext ctx, String label) {
        try {
            new GraphQLRewriteGenerator(ctx).generate();
            previousErrorKeys = Set.of();
            getLog().info("graphitron:dev: " + label + " ok");
            return true;
        } catch (ValidationFailedException e) {
            String tree = WatchErrorFormatter.format(e.errors(), previousErrorKeys);
            previousErrorKeys = WatchErrorFormatter.keysOf(e.errors());
            getLog().error("graphitron:dev: " + label + " failed validation\n" + tree);
            return false;
        } catch (RuntimeException e) {
            previousErrorKeys = null;
            getLog().error("graphitron:dev: " + label + " failed (infrastructure)", e);
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
        return "── graphitron:dev: " + label + " ──";
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
        // Watch every reactor project's target/classes so service/condition/record
        // classes declared in sibling modules also trigger rebuilds.
        var roots = new java.util.LinkedHashSet<Path>();
        for (Path root : ctx.classpathRoots()) {
            if (java.nio.file.Files.isDirectory(root)) {
                roots.add(root);
            }
        }
        if (roots.isEmpty()) {
            // Fallback for unit tests / non-reactor invocations: use
            // basedir's own target/classes when populated.
            Path own = ctx.basedir().resolve("target/classes");
            if (java.nio.file.Files.isDirectory(own)) {
                roots.add(own);
            }
        }
        return roots;
    }
}
