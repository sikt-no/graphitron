package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.SchemaParseException;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.ValidationReport;
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
import java.util.function.Consumer;

/**
 * Single user-facing entry point for editing graphitron schemas. Runs the
 * LSP server and the schema-input watch loop in one JVM, one terminal:
 *
 * <ul>
 *   <li>Binds {@code 127.0.0.1:8487} (override via
 *       {@code -Dgraphitron.dev.port=N}) and serves the LSP to whoever
 *       connects.</li>
 *   <li>Watches {@code <schemaInputs>} for writes matching the configured
 *       {@code <schemaFileExtensions>} (default {@code .graphqls} /
 *       {@code .graphql}) and re-runs the generator on every save (debounced).
 *       Editor saves arriving over the LSP fire the same trigger directly,
 *       bypassing the filesystem watcher's latency on platforms where it
 *       polls (see R198).</li>
 *   <li>Watches every reactor project's {@code target/classes} for
 *       {@code .class} changes and rebuilds the in-process catalog
 *       atomically. Both jOOQ output (tables / columns / FKs) and
 *       consumer service / condition / record classes — whether declared
 *       in the schema module or a sibling reactor module — flow through
 *       the same rebuild trigger.</li>
 *   <li>Watches every reactor project's compile source roots for
 *       {@code .java} changes and refreshes the LSP's source-position index
 *       on that source cadence, decoupled from the {@code .class} rebuild
 *       (R349). Service-half goto-definition reads positions from this index,
 *       so a declaration that moves in a hand-edited source file is jumpable
 *       without waiting for a recompile.</li>
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
    private SchemaWatcher sourceWatcher;
    private DebounceExecutor schemaDebounce;
    private DebounceExecutor classpathDebounce;
    private DebounceExecutor sourceDebounce;
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
        if (initial.snapshot() instanceof LspSchemaSnapshot.Built.Current current) {
            workspace.setBuildOutput(
                new GraphQLRewriteGenerator.BuildArtifacts(initial.catalog(), current),
                initial.report());
        }
        // Build the debounce and save-listener before bindServer so DevServer
        // can hand the listener to each editor-facing GraphitronLanguageServer.
        // LSP didSave fires this listener on the same debounce window the
        // filesystem watcher uses, so the two paths coalesce on a single regen.
        this.schemaDebounce = new DebounceExecutor(debounceMs);
        Consumer<String> saveListener = buildSaveListener(
            initialCtx.schemaFileExtensions(), schemaDebounce, () -> regenerate(workspace));
        bindServer(workspace, saveListener);
        // Seed the source-position index so goto-definition works before the
        // first .java edit; the source watcher refreshes it on the source
        // cadence thereafter. Path-only read on initialCtx (no loader).
        workspace.setSourceIndex(SourceWalker.walk(initialCtx.compileSourceRoots()));
        // Diagnostic so a "completion works but goto-definition returns nothing"
        // report can be traced to a module whose classes are scanned but whose
        // source root is not walked (R351): the two counts should track each other.
        getLog().info("graphitron:dev: scanning " + initialCtx.classpathRoots().size()
            + " reactor classpath root(s), " + initialCtx.compileSourceRoots().size()
            + " source root(s); " + workspace.catalog().externalReferences().size()
            + " external reference(s) indexed");
        Set<Path> schemaRoots = startSchemaWatcher(initialCtx, workspace);
        startClasspathWatcher(initialCtx, workspace);
        startSourceWatcher(initialCtx, workspace);

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

    private void bindServer(Workspace workspace, Consumer<String> saveListener) throws MojoExecutionException {
        try {
            this.server = new DevServer(new InetSocketAddress(LOOPBACK_HOST, port), workspace, saveListener);
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
        try {
            this.schemaWatcher = new SchemaWatcher(
                roots, schemaDebounce, () -> regenerate(workspace), ctx.schemaFileExtensions());
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

    /**
     * Starts the {@code .java} source-root watcher (R349). Walks the same
     * compile source roots the catalog build uses, but on the source cadence:
     * a hand-edited service / condition source refreshes the LSP's
     * source-position index without waiting for a {@code .class} rebuild. The
     * walk is parse-only (no reflection, no classpath resolution), so it runs
     * straight off the captured {@code ctx}'s path fields without a codegen
     * scope, unlike the regenerate / rebuildCatalog triggers.
     */
    private void startSourceWatcher(RewriteContext ctx, Workspace workspace) throws MojoExecutionException {
        Set<Path> roots = resolveSourceRoots(ctx);
        if (roots.isEmpty()) {
            getLog().info("graphitron:dev: skipping source watcher; "
                + "no compile source roots resolved (goto-definition positions stay at startup walk)");
            return;
        }
        this.sourceDebounce = new DebounceExecutor(debounceMs);
        try {
            this.sourceWatcher = new SchemaWatcher(
                roots, sourceDebounce, () -> refreshSourceIndex(ctx, workspace), ".java");
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron:dev: failed to start source watcher", e);
        }
        Thread sourceThread = new Thread(sourceWatcher::run, "graphitron-dev-source");
        sourceThread.setDaemon(true);
        sourceThread.start();
    }

    private void refreshSourceIndex(RewriteContext ctx, Workspace workspace) {
        try {
            workspace.setSourceIndex(SourceWalker.walk(ctx.compileSourceRoots()));
            getLog().info("graphitron:dev: source change detected; refreshed goto-definition positions");
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: source-position refresh failed; keeping previous: "
                + e.getMessage());
        }
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
                    workspace.setBuildOutput(output.artifacts(), output.report());
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
                    var output = new GraphQLRewriteGenerator(ctx).buildOutput();
                    workspace.setBuildOutput(output.artifacts(), output.report());
                    var catalog = output.artifacts().catalog();
                    getLog().info("graphitron:dev: catalog refreshed (" + catalog.tables().size()
                        + " tables, " + catalog.types().size() + " scalars)");
                } catch (RuntimeException e) {
                    // Bad schema mid-edit: keep the previous catalog so completions
                    // do not silently disappear, demote snapshot to Built.Previous so
                    // freshness-aware consumers silence themselves. The next save
                    // will re-trigger.
                    getLog().warn("graphitron:dev: catalog rebuild failed; keeping previous: "
                        + e.getMessage());
                    workspace.demoteSnapshot();
                    workspace.markAllForRecalculation();
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
            return new InitialOutput(output.artifacts().catalog(), output.artifacts().snapshot(), output.report());
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: initial catalog build failed; "
                + "starting with empty catalog: " + e.getMessage());
            return new InitialOutput(CompletionData.empty(), LspSchemaSnapshot.unavailable(), ValidationReport.empty());
        }
    }

    /**
     * Carrier for {@link #buildOutputQuietly}'s output. {@code snapshot} is {@link LspSchemaSnapshot}
     * rather than {@link LspSchemaSnapshot.Built.Current} because the failure path returns
     * {@link LspSchemaSnapshot.Unavailable}; the success path narrows back to {@code Built.Current}
     * via an {@code instanceof} check at the call site before constructing
     * {@link GraphQLRewriteGenerator.BuildArtifacts}.
     */
    private record InitialOutput(CompletionData catalog, LspSchemaSnapshot snapshot, ValidationReport report) {}

    // Package-private so DevMojoTest can drive the catch-arm discrimination directly
    // (a malformed schema vs a missing file) without standing up the full watch loop.
    boolean runGeneratorPass(RewriteContext ctx, String label) {
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
        } catch (SchemaParseException e) {
            // An invalid intermediate schema mid-edit is expected and author-correctable;
            // surface the attributed file:line:col one-liner without the throwable, so the
            // dev log shows one clean line instead of the graphql-java + executor stack.
            // Not a validator verdict, so it must not feed WatchErrorFormatter's delta
            // tracker: reset so the next successful validation reports its full error set.
            previousErrorKeys = null;
            getLog().error("graphitron:dev: " + label + " failed: " + e.getMessage());
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
        if (sourceWatcher != null) sourceWatcher.close();
        if (schemaDebounce != null) schemaDebounce.close();
        if (classpathDebounce != null) classpathDebounce.close();
        if (sourceDebounce != null) sourceDebounce.close();
        if (server != null) server.close();
    }

    private static String banner(String label) {
        return "── graphitron:dev: " + label + " ──";
    }

    /**
     * Listener fed to {@link DevServer} and through it to each
     * {@link no.sikt.graphitron.lsp.server.GraphitronLanguageServer}. Fires
     * only for URIs whose path ends with one of the configured schema
     * extensions; non-schema saves (e.g. {@code .md}) are silently dropped.
     * The LSP module stays suffix-agnostic — extension-set ownership lives
     * here, in the Mojo, alongside {@link RewriteContext#schemaFileExtensions()}.
     */
    static Consumer<String> buildSaveListener(Set<String> suffixes, DebounceExecutor debounce, Runnable regen) {
        return uri -> {
            if (suffixes.stream().anyMatch(uri::endsWith)) {
                debounce.schedule(regen);
            }
        };
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

    private static Set<Path> resolveSourceRoots(RewriteContext ctx) {
        // Watch every reactor project's compile source roots (hand-written plus
        // generated-sources) so service / condition / record sources in sibling
        // modules also refresh goto-definition positions. Same roots the catalog
        // build walks; here on the source cadence.
        var roots = new LinkedHashSet<Path>();
        for (Path root : ctx.compileSourceRoots()) {
            if (java.nio.file.Files.isDirectory(root)) {
                roots.add(root);
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
