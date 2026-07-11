package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.SchemaParseException;
import no.sikt.graphitron.rewrite.ValidationFailedException;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.compile.CompileOutcome;
import no.sikt.graphitron.rewrite.compile.IncrementalCompiler;
import no.sikt.graphitron.rewrite.maven.dev.DevServer;
import no.sikt.graphitron.mcp.DevQueryExecutor;
import no.sikt.graphitron.mcp.ExecuteTool;
import no.sikt.graphitron.mcp.GraphitronMcpServer;
import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.RagConfig;
import no.sikt.graphitron.mcp.rag.RagLogQuieting;
import no.sikt.graphitron.mcp.rag.WarmState;
import no.sikt.graphitron.mcp.rag.docs.DocsIndex;
import no.sikt.graphitron.mcp.rag.docs.DocsRag;
import no.sikt.graphitron.rewrite.maven.watch.CompileErrorFormatter;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
// TEST resolution (not COMPILE like the sibling goals): the R428 execute tool's classloader needs
// the consumer's JDBC driver, and the driver is conventionally NOT on the compile classpath: a
// plain app has it at runtime scope, and a Quarkus app often has no driver in its Maven graph at
// all (the extension resolves it at Quarkus build time) except the test-scope driver its
// jOOQ-codegen/database tests already use. TEST is the superset scope, so the compile classpath
// the incremental compiler scans is unaffected, and the execute loader orders main elements first
// so test classes can never shadow production ones.
@Mojo(
    name = "dev",
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true
)
public class DevMojo extends AbstractRewriteMojo {

    static final int DEFAULT_PORT = 8487;
    static final int DEFAULT_MCP_PORT = 8488;
    static final String LOOPBACK_HOST = "127.0.0.1";

    @Parameter(property = "graphitron.dev.port", defaultValue = "8487")
    int port;

    // The MCP server's loopback port. Deliberately NOT a @Parameter: it stays non-overridable for
    // users in the skeleton (a configurable port is deferred; see R341), while remaining settable
    // from DevMojoTest so the bind-failure case can inject a taken ephemeral port instead of the
    // well-known 8488. Mirrors DEFAULT_PORT/port.
    int mcpPort = DEFAULT_MCP_PORT;

    @Parameter(property = "graphitron.dev.debounceMs", defaultValue = "300")
    long debounceMs;

    @Parameter(property = "graphitron.dev.skipInitial", defaultValue = "false")
    boolean skipInitial;

    /**
     * R410 slice 6 — whether {@code graphitron:dev} compiles the generated sources into
     * {@code target/graphitron-classes} (in-process, incrementally). On by default: the compiled tree is
     * what the in-process MCP query tools execute against. Set {@code -Dgraphitron.dev.compile=false} to
     * fall back to today's generate-only behaviour (giving up the in-process query tools too). No
     * fail-fast: because the output dir is graphitron-exclusive, a mis-set-up consumer degrades to
     * generate-only rather than corrupting bytecode, so there is nothing to fail on.
     */
    @Parameter(property = "graphitron.dev.compile", defaultValue = "true")
    boolean compile = true;

    /**
     * R428 — the dev database the MCP {@code execute} tool runs queries against. Optional: with no
     * {@code <devDatabase>} url (and no {@code GRAPHITRON_DEV_DB_URL} env override) the execute tool
     * is simply absent and every other dev tool works with no database. See
     * {@link DevDatabaseBinding} for the block shape and the env-wins override set.
     */
    @Parameter
    DevDatabaseBinding devDatabase;

    // The environment the <devDatabase> reconciler reads its overrides from. Production is
    // System.getenv(); package-private so DevMojoTest can inject a map without mutating the JVM's
    // real environment.
    Map<String, String> environment = System.getenv();

    private SchemaWatcher schemaWatcher;
    private SchemaWatcher classpathWatcher;
    private SchemaWatcher sourceWatcher;
    private DebounceExecutor schemaDebounce;
    private DebounceExecutor classpathDebounce;
    private DebounceExecutor sourceDebounce;
    // Package-private so DevMojoTest can assert the LSP socket is closed when a partial bind
    // (the MCP server failing after the LSP succeeds) is unwound in bindServer.
    DevServer server;
    private GraphitronMcpServer mcpServer;
    // R385 — the two RAG warms docs.search rides: a shared bge embedder load (heavy, off the dev
    // thread) and the docs-index load (reads the bundled tuples, rebuilds the in-memory store). Both
    // start during bind and never block it; a warm failure leaves the dev loop structured-only.
    // Package-private so DevMojoTest can read their terminal state after a bind-failure unwind.
    AsyncWarm<Embedder> embedderWarm;
    AsyncWarm<DocsIndex> docsWarm;
    // The warm factories, behind a seam mirroring GraphitronMcpServer's structured-only / injected-warm
    // constructor pair. Production keeps the DocsRag factories; DevMojoTest swaps in ONNX-free fakes so
    // the fast surefire fork never pays a real BgeEmbedder ONNX load (which SIGSEGVs the fork). A
    // factory may return null to run structured-only, exactly as the server tolerates null warms.
    java.util.function.Supplier<AsyncWarm<Embedder>> embedderWarmFactory = DocsRag::embedderWarm;
    java.util.function.Supplier<AsyncWarm<DocsIndex>> docsWarmFactory = DocsRag::docsWarm;
    private Set<WatchErrorFormatter.DeltaKey> previousErrorKeys = null;
    // R410 slice 6 — the long-lived incremental compile driver (warm compiler + ABI-hash baseline),
    // built at startup when compilation is enabled and closed on shutdown. Null when
    // -Dgraphitron.dev.compile=false, or when no system compiler is available (graceful degrade to
    // generate-only). Package-private so DevMojoTest can assert the opt-out leaves it unbuilt.
    IncrementalCompiler incrementalCompiler;
    // The last successful generation (result + compile graph), captured by runGeneratorPass. The
    // consumer-.class-change path recompiles the whole cached tree off this; the schema-save path
    // recompiles the delta against its graph. Volatile: written by the schema-watcher thread and read
    // by the classpath-watcher thread, so the consumer-change path must see the freshest generation.
    private volatile GraphQLRewriteGenerator.IncrementalGeneration lastGeneration;

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
                new GraphQLRewriteGenerator.BuildArtifacts(initial.catalog(), current, initial.catalogFacts()),
                initial.report());
        }
        // Build the debounce and save-listener before bindServer so DevServer
        // can hand the listener to each editor-facing GraphitronLanguageServer.
        // LSP didSave fires this listener on the same debounce window the
        // filesystem watcher uses, so the two paths coalesce on a single regen.
        this.schemaDebounce = new DebounceExecutor(debounceMs);
        Consumer<String> saveListener = buildSaveListener(
            initialCtx.schemaFileExtensions(), schemaDebounce, () -> regenerate(workspace));
        bindServer(workspace, saveListener, new RagConfig(resolveRagCacheDirectory(initialCtx.basedir())),
            buildExecuteToolConfig(initialCtx));
        // Seed the source-position index so goto-definition / hover work before
        // the first .java edit; the source watcher refreshes it on the source
        // cadence thereafter. The walk (and its cache) is owned by the workspace.
        // Path-only read on initialCtx (no loader).
        workspace.refreshSourceIndex(initialCtx.compileSourceRoots());
        // Diagnostic so a "completion works but goto-definition returns nothing"
        // report can be traced to a module whose classes are scanned but whose
        // source root is not walked (R351): the two counts should track each other.
        getLog().info("graphitron:dev: scanning " + initialCtx.classpathRoots().size()
            + " reactor classpath root(s), " + initialCtx.compileSourceRoots().size()
            + " source root(s); " + workspace.catalog().externalReferences().size()
            + " external reference(s) indexed");
        // Self-explain the single-module-reactor case the sibling walk-up (R99) could not
        // widen: when graphitron:dev runs from inside a sub-module and no ancestor pom lists
        // it (so no siblings were resolved), only this module's target/classes is scanned.
        // Without this line the symptom is a silent empty popup with nothing to grep for.
        if (singleProjectReactor() && siblingModuleBasedirs().isEmpty()) {
            getLog().info("graphitron:dev: this reactor resolved to a single module and no "
                + "sibling modules were found to scan. If services / conditions / records "
                + "live in sibling modules, run from the aggregator (e.g. mvn -pl <module> "
                + "graphitron:dev) or check that the parent pom's <modules> lists this module "
                + "(see R99).");
        }
        // Name any module the auto-include could not close: scanned for completion
        // (its target/classes is on disk) but contributing no walked source root, so
        // goto-definition / hover on its declarations is a silent no-jump (R369). The
        // common residue is a table arriving only as a dependency JAR with no .java.
        var unwalked = unwalkedScannedModules();
        if (!unwalked.isEmpty()) {
            getLog().warn("graphitron:dev: " + unwalked.size()
                + " scanned reactor module(s) contribute no walked source root, so "
                + "goto-definition / hover on their declarations returns nothing: "
                + String.join(", ", unwalked)
                + ". Build a module to put its generated sources on disk; a table that "
                + "arrives only as a dependency JAR has no source to walk (see R369).");
        }
        // Build the warm compiler and compile the whole generated tree before the watchers start, so
        // the exclusive dir holds a complete runnable image the MCP query tools can execute against
        // from the first edit. Must precede the classpath watcher: its rebuildCatalog callback drives
        // the consumer-change recompile off this same driver.
        maybeStartIncrementalCompiler(workspace);
        Set<Path> schemaRoots = startSchemaWatcher(initialCtx, workspace);
        startClasspathWatcher(initialCtx, workspace);
        startSourceWatcher(initialCtx, workspace);

        Thread shutdown = new Thread(this::cleanup, "graphitron-dev-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);

        getLog().info("graphitron:dev: LSP listening on " + LOOPBACK_HOST + ":" + server.port()
            + "; watching " + schemaRoots + "; Ctrl+C to stop");
        String mcpUrl = "http://" + LOOPBACK_HOST + ":" + mcpServer.port() + "/mcp";
        getLog().info("graphitron:dev: MCP server on " + mcpUrl + " (Streamable HTTP, loopback only)");
        getLog().info("graphitron:dev: connect an agent with: "
            + "claude mcp add --transport http graphitron " + mcpUrl);
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

    private void bindServer(Workspace workspace, Consumer<String> saveListener, RagConfig ragConfig,
        ExecuteTool.Config executeConfig)
        throws MojoExecutionException {
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
        // Quiet the RAG warms' non-actionable startup log noise before the warms start (R409). The
        // warms load the noisy classes (DJL tokenizer, Lucene VectorizationProvider) on their
        // graphitron-warm-* daemon threads, and thread-start is a happens-before edge, so establishing
        // the suppression on this dev thread first guarantees it is visible when those threads touch
        // the loggers. Do not reorder this after the start() calls below. The helper lives in
        // graphitron-mcp because the logger names are facts about the quarantined RAG dependency set.
        RagLogQuieting.quietRagWarmLogs(getLog()::info);
        // Start the RAG warms before binding the MCP server so they warm during startup; both run on
        // their own daemon threads and never block the bind (R372 / R118). The shared embedder warm
        // is stood up here so slice 10 can later reuse the same handle rather than loading a second
        // bge copy. Both come from the injectable factories (see the field comment), so the heavy ONNX
        // load stays out of the fast test suite; a factory returning null runs structured-only.
        this.embedderWarm = embedderWarmFactory.get();
        this.docsWarm = docsWarmFactory.get();
        if (this.embedderWarm != null) {
            this.embedderWarm.start();
        }
        if (this.docsWarm != null) {
            this.docsWarm.start();
        }
        // The MCP server is a sibling of the LSP DevServer in the same JVM. A failed MCP bind must
        // not leak the LSP socket already bound above, so close it before rethrowing. Jetty wraps a
        // taken port as a plain IOException (not BindException), so a single arm covers it; the
        // message names the MCP port and gives recovery guidance, mirroring the LSP arm's contract.
        try {
            this.mcpServer = new GraphitronMcpServer(
                new InetSocketAddress(LOOPBACK_HOST, mcpPort), workspace, embedderWarm, docsWarm, ragConfig,
                executeConfig);
        } catch (IOException e) {
            // The partial-startup unwind must reach the warms too, not just the LSP socket: warm
            // cleanup otherwise lives only in cleanup() (the normal Ctrl+C stop), which this exception
            // path never reaches. Unlike that path, a failed bind returns into a still-live JVM, so a
            // warm left mid-load would keep running on its daemon thread after this method returns
            // (with a real ONNX load, a leaked embedder daemon mid-load crashes a surefire fork).
            this.server.close();
            awaitAndCloseWarms();
            throw new MojoExecutionException(
                "graphitron:dev: MCP port " + mcpPort + " is already in use (or could not be bound). "
                    + "Stop the other graphitron:dev session occupying it, then retry.", e);
        }
    }

    /**
     * Reconciles the {@code <devDatabase>} block with its environment overrides into the R428
     * execute-tool configuration; env wins over the POM on every field, so credentials stay out of
     * the checked-in file. Returns {@code null}, and the execute tool is simply not registered,
     * when no url is configured from either source (the degrade-gracefully arm: every other MCP
     * tool works with no database). A url with a missing or unsupported dialect fails the goal
     * loudly instead: the dialect is explicit and enumerated ({@code POSTGRES} / {@code ORACLE}),
     * never defaulted, and a half-configured dev database is a config bug, not a degrade case.
     * The claims payload stays raw here (inline or {@code @file}); the tool resolves the
     * {@code @file} form per call so file edits apply without a restart.
     */
    ExecuteTool.Config buildExecuteToolConfig(RewriteContext ctx) throws MojoExecutionException {
        DevDatabase devDb = resolveDevDatabase();
        if (devDb == null) {
            return null;
        }
        var wiring = new DevQueryExecutor.Wiring(
            ctx.outputPackage(),
            resolveGraphitronClassesDirectory(ctx.basedir()),
            resolveExecutionClasspath());
        return new ExecuteTool.Config(wiring, devDb.db(), devDb.allowClaimsOverride());
    }

    /**
     * The executor loader's classpath: the compile classpath the incremental compiler already
     * scans (first, so main classes always win), widened with the runtime- and test-scoped
     * elements (available because this goal resolves {@code TEST}, the superset scope). The
     * widening is what puts the consumer's JDBC driver on the loader: a plain app carries the
     * driver at runtime scope, and a Quarkus app typically carries it only at test scope (the
     * extension resolves the real driver at Quarkus build time, outside the Maven graph), so the
     * compile classpath alone never sees it.
     */
    private List<Path> resolveExecutionClasspath() throws MojoExecutionException {
        var paths = new LinkedHashSet<>(resolveCompileClasspath());
        try {
            for (String element : project.getTestClasspathElements()) {
                paths.add(Path.of(element).toAbsolutePath().normalize());
            }
        } catch (org.apache.maven.artifact.DependencyResolutionRequiredException e) {
            throw new MojoExecutionException(
                "Failed to assemble the execution classpath for the dev execute tool.", e);
        }
        return new ArrayList<>(paths);
    }

    /** The reconciled dev database coordinates, before the executor wiring joins them. */
    record DevDatabase(DevQueryExecutor.DbConfig db, boolean allowClaimsOverride) {}

    /**
     * The pure half of {@link #buildExecuteToolConfig}: merges the {@code <devDatabase>} block
     * with its environment overrides (env wins on every field) and validates the dialect. Returns
     * {@code null} when no url is configured from either source.
     */
    DevDatabase resolveDevDatabase() throws MojoExecutionException {
        String url = firstNonBlank(environment.get("GRAPHITRON_DEV_DB_URL"),
            devDatabase == null ? null : devDatabase.url);
        if (url == null) {
            getLog().info("graphitron:dev: no dev database configured (<devDatabase> url or "
                + "GRAPHITRON_DEV_DB_URL); the MCP execute tool is disabled, every other tool "
                + "works without it.");
            return null;
        }
        String dialect = firstNonBlank(environment.get("GRAPHITRON_DEV_DB_DIALECT"),
            devDatabase == null ? null : devDatabase.dialect);
        if (dialect == null) {
            throw new MojoExecutionException(
                "graphitron:dev: a dev database url is configured but no dialect. The dialect is "
                    + "explicit and enumerated, never defaulted: set <devDatabase><dialect>POSTGRES"
                    + "</dialect> (or ORACLE), or GRAPHITRON_DEV_DB_DIALECT.");
        }
        String normalizedDialect = dialect.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalizedDialect.equals("POSTGRES") && !normalizedDialect.equals("ORACLE")) {
            throw new MojoExecutionException(
                "graphitron:dev: unsupported dev database dialect '" + dialect
                    + "'; POSTGRES and ORACLE are the supported values.");
        }
        String user = firstNonBlank(environment.get("GRAPHITRON_DEV_DB_USER"),
            devDatabase == null ? null : devDatabase.user);
        String password = firstNonBlank(environment.get("GRAPHITRON_DEV_DB_PASSWORD"),
            devDatabase == null ? null : devDatabase.password);
        String claims = firstNonBlank(environment.get("GRAPHITRON_DEV_CLAIMS"),
            devDatabase == null ? null : devDatabase.claims);
        boolean allowClaimsOverride = environment.containsKey("GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE")
            ? Boolean.parseBoolean(environment.get("GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE"))
            : devDatabase != null && Boolean.TRUE.equals(devDatabase.allowClaimsOverride);
        return new DevDatabase(
            new DevQueryExecutor.DbConfig(url, user, password, normalizedDialect, claims),
            allowClaimsOverride);
    }

    private static String firstNonBlank(String env, String pom) {
        if (env != null && !env.isBlank()) {
            return env;
        }
        return pom != null && !pom.isBlank() ? pom : null;
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
            workspace.refreshSourceIndex(ctx.compileSourceRoots());
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
                boolean generated = runGeneratorPass(ctx, "regenerate");
                // A clean regen produces the writer's delta + this schema's compile graph; recompile
                // just the affected sub-closure into the exclusive dir. A failed regen leaves the last
                // good .class in place (nothing to recompile from), matching the generate-only path.
                if (generated && incrementalCompiler != null && lastGeneration != null) {
                    var gen = lastGeneration;
                    var outcome = incrementalCompiler.recompile(
                        gen.result().emittedUnits(), gen.result().changedUnits(), gen.graph());
                    reportCompile(workspace, outcome, "recompile");
                }
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
                    // A consumer .class changed: a generated unit that compiles against it may now be
                    // stale. The compile graph carries only generated→generated edges (no
                    // generated→consumer edge to walk), so invalidate conservatively by recompiling the
                    // whole cached generated tree. Precise generated→consumer invalidation is deferred,
                    // and belongs with R333's method graph.
                    if (incrementalCompiler != null && lastGeneration != null) {
                        var outcome = incrementalCompiler.compileAll(lastGeneration.result().emittedUnits());
                        reportCompile(workspace, outcome, "recompile (consumer classpath change)");
                    }
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
            return new InitialOutput(output.artifacts().catalog(), output.artifacts().snapshot(),
                output.artifacts().catalogFacts(), output.report());
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: initial catalog build failed; "
                + "starting with empty catalog: " + e.getMessage());
            return new InitialOutput(CompletionData.empty(), LspSchemaSnapshot.unavailable(),
                CatalogFacts.empty(), ValidationReport.empty());
        }
    }

    /**
     * Carrier for {@link #buildOutputQuietly}'s output. {@code snapshot} is {@link LspSchemaSnapshot}
     * rather than {@link LspSchemaSnapshot.Built.Current} because the failure path returns
     * {@link LspSchemaSnapshot.Unavailable}; the success path narrows back to {@code Built.Current}
     * via an {@code instanceof} check at the call site before constructing
     * {@link GraphQLRewriteGenerator.BuildArtifacts}.
     */
    private record InitialOutput(CompletionData catalog, LspSchemaSnapshot snapshot,
                                 CatalogFacts catalogFacts, ValidationReport report) {}

    // Package-private so DevMojoTest can drive the catch-arm discrimination directly
    // (a malformed schema vs a missing file) without standing up the full watch loop.
    boolean runGeneratorPass(RewriteContext ctx, String label) {
        // Cleared up front so a failed pass never leaves a stale generation for the compile driver to
        // act on; reassigned only on a clean generate below.
        this.lastGeneration = null;
        try {
            var generator = new GraphQLRewriteGenerator(ctx);
            // When compiling, capture the emitted TypeSpecs + compile graph the incremental driver reads;
            // otherwise stay on the cheaper generate().
            if (compile) {
                this.lastGeneration = generator.generateIncremental();
            } else {
                generator.generate();
            }
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

    /**
     * Builds the warm incremental compile driver and compiles the whole generated tree once, unless
     * {@code -Dgraphitron.dev.compile=false} opts out. No fail-fast: if the driver cannot be built (a
     * JRE with no system compiler, or a classpath-assembly failure), it degrades to generate-only for
     * the session with a warning rather than aborting the dev loop. Called once at startup, before the
     * watchers, so the exclusive dir holds a complete image and the consumer-change path has a driver.
     */
    void maybeStartIncrementalCompiler(Workspace workspace) {
        if (!compile) {
            getLog().info("graphitron:dev: -Dgraphitron.dev.compile=false; "
                + "generating without compiling (in-process query tools unavailable)");
            return;
        }
        Path classesDir = resolveGraphitronClassesDirectory(project.getBasedir().toPath());
        try {
            this.incrementalCompiler = new IncrementalCompiler(classesDir, resolveCompileClasspath());
        } catch (Exception e) {
            getLog().warn("graphitron:dev: incremental compile unavailable; "
                + "generating without compiling this session: " + e.getMessage());
            this.incrementalCompiler = null;
            return;
        }
        getLog().info("graphitron:dev: compiling generated classes into " + classesDir
            + " (put this ahead of target/classes on your run classpath; not for quarkus:dev, see docs)");
        if (lastGeneration != null) {
            var outcome = incrementalCompiler.compileAll(lastGeneration.result().emittedUnits());
            reportCompile(workspace, outcome, "initial compile");
        } else {
            // No initial generation happened this startup (skipInitial, or the initial pass failed), so
            // there are no in-memory TypeSpecs to compile the whole tree from. The exclusive dir fills in
            // incrementally from the first save's recompile rather than as a complete image up front.
            getLog().info("graphitron:dev: no initial generation; the generated-class image fills in "
                + "from the first save (skip -Dgraphitron.dev.skipInitial to compile the whole tree at startup)");
        }
    }

    /**
     * Surfaces one compile round through the two channels the spec names: the console (a labelled
     * generated-code block on failure via {@link CompileErrorFormatter}, a one-line summary on success)
     * and the MCP {@code diagnostics} tool (via {@code Workspace.setCompileDiagnostics}, tagged
     * {@code source:"compile"}). The round's full diagnostic list is published even on success so a prior
     * failure is cleared once it resolves.
     */
    void reportCompile(Workspace workspace, CompileOutcome outcome, String label) {
        var round = outcome.round();
        workspace.setCompileDiagnostics(round.diagnostics());
        if (round.success()) {
            getLog().info("graphitron:dev: " + label + " compiled "
                + outcome.compiledUnits().size() + " unit(s) ok");
        } else {
            getLog().error("graphitron:dev: " + label + "\n"
                + CompileErrorFormatter.format(round.errors()));
        }
    }

    private void cleanup() {
        if (incrementalCompiler != null) incrementalCompiler.close();
        if (schemaWatcher != null) schemaWatcher.close();
        if (classpathWatcher != null) classpathWatcher.close();
        if (sourceWatcher != null) sourceWatcher.close();
        if (schemaDebounce != null) schemaDebounce.close();
        if (classpathDebounce != null) classpathDebounce.close();
        if (sourceDebounce != null) sourceDebounce.close();
        if (server != null) server.close();
        if (mcpServer != null) mcpServer.close();
        // Close the docs store if it warmed (frees the in-memory Lucene index); a still-warming or
        // failed warm has no store to close. The embedder warm holds no closeable resource. Daemon
        // warm threads die with the JVM regardless.
        if (docsWarm != null && docsWarm.state() instanceof WarmState.Ready<DocsIndex> ready) {
            ready.handle().close();
        }
    }

    /**
     * Tears down the RAG warms on the bind-failure unwind, before the fatal
     * {@link MojoExecutionException} propagates. Distinct from {@link #cleanup()} (the normal Ctrl+C
     * stop, where the JVM is exiting and the daemon warm threads die with it): a failed bind returns
     * into a still-live JVM, so a warm left mid-load would keep running on its daemon thread after this
     * method returns. So join each warm to its terminal state ({@link AsyncWarm#await()}) before closing
     * the docs store if it warmed; the embedder warm holds no closeable resource. Joining is what
     * guarantees no warm daemon outlives the unwind (and, with a real ONNX load, keeps a leaked embedder
     * daemon from crashing a surefire fork after the test returns).
     */
    private void awaitAndCloseWarms() {
        if (embedderWarm != null) {
            embedderWarm.await();
        }
        if (docsWarm != null && docsWarm.await() instanceof WarmState.Ready<DocsIndex> ready) {
            ready.handle().close();
        }
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
