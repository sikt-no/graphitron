package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.capture.CapturePort;
import no.sikt.graphitron.rewrite.capture.GraphIdentity;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.lsp.state.StoreAccess;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.SchemaParseException;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.derive.RefreshProgress;
import no.sikt.graphitron.model.boot.ReadBudget;
import no.sikt.graphitron.model.boot.StoreConsole;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.capture.JavaSourceFacts;
import no.sikt.graphitron.rewrite.capture.SourceWalker;
import no.sikt.graphitron.rewrite.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.compile.CompileFacts;
import no.sikt.graphitron.rewrite.compile.CompileOutcome;
import no.sikt.graphitron.rewrite.diagnostics.BuildWarningFacts;
import no.sikt.graphitron.rewrite.diagnostics.RejectionFacts;
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
 *       polls.</li>
 *   <li>Watches every reactor project's {@code target/classes} for
 *       {@code .class} changes and rebuilds the in-process catalog
 *       atomically. Both jOOQ output (tables / columns / FKs) and
 *       consumer service / condition / record classes — whether declared
 *       in the schema module or a sibling reactor module — flow through
 *       the same rebuild trigger.</li>
 *   <li>Watches every reactor project's compile source roots for
 *       {@code .java} changes and refreshes the store's {@code java_} family
 *       on that source cadence, decoupled from the {@code .class} rebuild.
 *       Service-half goto-definition reads declaration positions from those
 *       rows, so a declaration that moves in a hand-edited source file is
 *       jumpable without waiting for a recompile.</li>
 * </ul>
 *
 * <p>Stop with Ctrl+C. See the "Dev loop" how-to in the user manual for the
 * editor-side recipes.
 */
// TEST resolution (not COMPILE like the sibling goals): the execute tool's classloader needs
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

    /**
     * The budget every keystroke-grain store read runs under: hovers, completions,
     * goto-definition, inlay hints, lint quick-fixes. Named rather than spelled at the mint so the
     * budgets below cannot drift apart unnoticed. Two readers run under it, the cursor's and the
     * annotation door's, which is a split of the queue and not of the guard.
     *
     * <p>Low seconds, which is far above anything a healthy read costs and deliberately not a
     * latency policy. The target is a query that would otherwise never return: a threshold tight
     * enough to police slowness would start refusing correct answers on a loaded machine, and
     * {@code LspTrace}'s own slow-span threshold is where latency gets reported.
     */
    static final ReadBudget INTERACTIVE_READ_BUDGET = new ReadBudget.Bounded(3_000);

    /**
     * The budget for the reads that are the session's rather than one cursor's: the whole-workspace
     * diagnostics drain and the directive-vocabulary load. Larger than the interactive one because a
     * drain legitimately costs more (it answers for every open file at once) and because it is the
     * read that least wants to fail: losing it costs the developer every squiggle in the workspace,
     * where losing a hover costs one popup.
     */
    static final ReadBudget SESSION_READ_BUDGET = new ReadBudget.Bounded(30_000);

    /**
     * The budget the MCP server's reader runs under. Turn-scale rather than keystroke-scale: an
     * agent asked one question and is waiting for the answer to it, so there is no queue of pending
     * requests behind this one and no screen going stale while it runs.
     */
    static final ReadBudget MCP_READ_BUDGET = new ReadBudget.Bounded(60_000);

    @Parameter(property = "graphitron.dev.port", defaultValue = "8487")
    int port;

    // The MCP server's loopback port. Deliberately NOT a @Parameter: not user-overridable, while
    // remaining settable from DevMojoTest so the bind-failure case can inject a taken ephemeral
    // port instead of the well-known 8488. Mirrors DEFAULT_PORT/port.
    int mcpPort = DEFAULT_MCP_PORT;

    @Parameter(property = "graphitron.dev.debounceMs", defaultValue = "300")
    long debounceMs;

    @Parameter(property = "graphitron.dev.skipInitial", defaultValue = "false")
    boolean skipInitial;

    /**
     * Whether {@code graphitron:dev} compiles the generated sources into
     * {@code target/graphitron-classes} (in-process, incrementally). On by default: the compiled tree is
     * what the in-process MCP query tools execute against. Set {@code -Dgraphitron.dev.compile=false} to
     * fall back to generate-only behaviour (giving up the in-process query tools too). No
     * fail-fast: because the output dir is graphitron-exclusive, a mis-set-up consumer degrades to
     * generate-only rather than corrupting bytecode, so there is nothing to fail on.
     */
    @Parameter(property = "graphitron.dev.compile", defaultValue = "true")
    boolean compile = true;

    /**
     * The dev database the MCP {@code execute} tool runs queries against. Optional: with no
     * {@code <devDatabase>} url (and no {@code GRAPHITRON_DEV_DB_URL} env override) the execute tool
     * is simply absent and every other dev tool works with no database. See
     * {@link DevDatabaseBinding} for the block shape and the env-wins override set.
     */
    @Parameter
    DevDatabaseBinding devDatabase;

    /**
     * The read-only SQL console onto this session's own fact store, so a developer can query the
     * rows the session is answering from. Optional and off by default: with no {@code <storeConsole>}
     * block (and no {@code GRAPHITRON_DEV_STORE_CONSOLE} env override) no port is bound and the
     * session is unchanged, and the log says how to turn it on. See {@link StoreConsoleBinding} for
     * the block shape and the env-wins override set.
     */
    @Parameter
    StoreConsoleBinding storeConsole;

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
    // The two RAG warms docs.search rides: a shared bge embedder load (heavy, off the dev
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
    // The long-lived incremental compile driver (warm compiler + ABI-hash baseline),
    // built at startup when compilation is enabled and closed on shutdown. Null when
    // -Dgraphitron.dev.compile=false, or when no system compiler is available (graceful degrade to
    // generate-only). Package-private so DevMojoTest can assert the opt-out leaves it unbuilt.
    IncrementalCompiler incrementalCompiler;
    // The dev session's fact-store handle: one live handle over the store the session's generator
    // passes capture into, opened before the first of them runs and closed in cleanup(). Live and
    // shared on purpose: the passes, the compile-facts writer and every in-process reader see each
    // round through one database, so a written round is a visible round. It is one store rather
    // than a pass's own aliased onto the same file by H2, which is what lets a reader's budget and
    // a capture's lock timeout be settings of one session instead of two. Package-private so
    // DevMojoTest can inject an in-memory store.
    GraphitronModelStore sessionStore;
    // Every pass's capture, over sessionStore: the session opens one store and each pass writes
    // into the one its readers are on, so a round is written and read through one database rather
    // than through a database of the pass's own that H2 happened to alias onto the same file.
    // Closed ahead of the store in cleanup(); closing it gives back nothing, the port never owning
    // a store it was lent, unless a refusal demoted it to one of its own.
    CapturePort sessionCapture;
    // The language server's own reader over sessionStore, minted once the store opens and closed
    // ahead of it in cleanup(). Null for the bare mojos that never start a session.
    StoreAccess lspStore;
    // The MCP server's reader over sessionStore, for the tools whose answer is several queries and so
    // needs a transaction of its own rather than a savepoint inside the writer's. Minted and closed
    // beside lspStore; null for the bare mojos, exactly as that one is.
    StoreReader mcpStore;
    // The session's fact-store SQL console, or null when it is off (the default) or could not open.
    // A debug affordance, so it degrades: a console that will not open costs the developer a tool
    // and never the session. Closed ahead of the store in cleanup(), the link connection it holds
    // pointing at the store. Package-private for the same reason sessionStore is.
    StoreConsole storeConsoleHandle;
    // The javac_ family's writer over sessionStore, or null before the store opens (bare mojos in
    // the unit tier); reportCompile writes through it beside the console sink.
    CompileFacts compileFacts;
    // The java_ family's writer over the same handle. The dev session owns the source walk because
    // it owns the watcher that triggers it, and the store is where the walk's product goes: one
    // parse per changed file, one sink.
    JavaSourceFacts javaSourceFacts;
    // The walk itself, held across refreshes so its per-file cache stays warm. One instance per
    // session, which is what keeps the .java cadence from sharing state with anything else.
    private final SourceWalker sourceWalker = new SourceWalker();
    // The diagnostics stratum's schema-side writers over the same handle, constructed beside
    // compileFacts and fed the pre-fuse lists wherever the build output is published: the
    // rejection residue off the walk's error stream, the warning arms off the
    // suppression-filtered list.
    RejectionFacts rejectionFacts;
    BuildWarningFacts warningFacts;
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
        // Before anything opens a store, because this is the earliest point the goal can reach and
        // H2 reads the property once, when its first class initialises. It confines every H2 server
        // this JVM starts (the fact-store console below is the only one) to loopback.
        //
        // LOAD-BEARING, and deliberately not sufficient on its own: this goal can run in a JVM that
        // already loaded H2 (ModelCodegenDriver opens a store during an ordinary build), and there
        // the property has no effect at all. StoreConsole therefore verifies the bind after starting
        // the listener and refuses to keep it up when it cannot be confined. Do not read this line as
        // covering the requirement and drop that check.
        System.setProperty("h2.bindAddress", LOOPBACK_HOST);
        // Initial codegen and LSP catalog build both reflect on consumer classes, so they share
        // one URLClassLoader scope. Watchers that follow only resolve paths (no reflection); they
        // capture the ctx returned here, whose loader is *closed* by the time setup proceeds.
        //
        // LOAD-BEARING: code added below that uses `initialCtx` must read only path-shaped fields
        // (`schemaInputs`, `basedir`, `classpathRoots`). Calling `initialCtx.codegenLoader()` here
        // returns a closed URLClassLoader and would surface as a confusing ClassNotFoundException
        // on the next reflection attempt. Each file-change callback (regenerate / rebuildCatalog)
        // opens its own scope and is the right place to reach for a live loader.
        // The session store handle, opened before the first pass runs so that pass captures into it
        // rather than into a store of its own. openAt falls back to a private in-memory store on
        // any cache trouble, so this never fails the goal; either way the handle lives until
        // cleanup() and every capture and compile round is written and readable through it.
        //
        // Resolved from the project rather than from a context, which is what lets the open come
        // first: the store's home is a pure function of the module's base directory and this
        // goal's own parameters, so it needs none of what building a context needs.
        Path storeHome = resolveStoreDirectory(project.getBasedir().toPath());
        this.sessionStore = storeHome != null
            ? GraphitronModelStore.openAt(storeHome)
            : GraphitronModelStore.open();
        // What the open released from the cache home. The store's sweep runs once per home per JVM,
        // and this open is now always the session's first, so this is where the report surfaces on
        // every startup rather than only on one whose initial run was skipped.
        if (storeHome != null) {
            sessionStore.reaped().report(storeHome).ifPresent(getLog()::info);
        }
        // Every pass captures through here, into the store above. The port is handed a store it
        // does not own: a refusal still demotes it to a private one of its own, exactly as it did
        // when each pass opened its own store, and the session's readers stay on what they were
        // given either way.
        this.sessionCapture = CapturePort.over(sessionStore);

        var initialCtxHolder = new AtomicReference<RewriteContext>();
        var initialHolder = new AtomicReference<InitialOutput>();
        try {
            withCodegenScope(ctx -> {
                initialCtxHolder.set(ctx);
                if (skipInitial) {
                    // Nothing is emitted this startup by design, so the editor's products come from
                    // the reporting-only entry point rather than from a pass.
                    initialHolder.set(buildOutputQuietly(ctx));
                } else {
                    getLog().info(banner("initial run"));
                    initialHolder.set(InitialOutput.of(runGeneratorPass(ctx, "initial run")));
                }
            });
        } catch (MojoExecutionException | RuntimeException e) {
            // The store is open this early now, and the shutdown hook that would give it back is
            // not registered until the watchers are up. A startup that fails here (a module whose
            // <schemaInputs> match nothing is the ordinary way) would otherwise leave the workspace
            // file held for the rest of the Maven run, which is precisely the held-store trouble
            // the demotion warning tells users to go looking for.
            sessionStore.close();
            throw e;
        }
        var initialCtx = initialCtxHolder.get();
        var initial = initialHolder.get();
        // The store may hold graphs no pass of this session captures (it is shared by every module
        // of the workspace), and a warm partition whose capture was skipped because nothing changed
        // refreshes nothing of its own, so the materialized targets are refreshed here rather than
        // assumed current: the editor-facing readers below are read-only and would otherwise serve
        // the language server and MCP stale rows. After the initial pass, so this graph's own
        // targets are already current by the time it runs. Idempotent, and a no-op with no
        // registrations.
        // The pass boundary at info, so a start that stalls here is attributed rather than looking
        // like a slow boot, and the per-registration tier at debug for a re-run with -X.
        Materializations.refreshAll(sessionStore.dsl(),
            RefreshProgress.lines(getLog()::info, getLog()::debug));
        // After the refresh, so the linked relations include the refreshed materializations, and
        // before the watchers, so the console is up before the first round lands.
        this.storeConsoleHandle = startStoreConsole();
        this.compileFacts = new CompileFacts(sessionStore.dsl(),
            new GraphIdentity(initialCtx.graphName(), initialCtx.basedir()));
        this.rejectionFacts = new RejectionFacts(sessionStore.dsl(),
            new GraphIdentity(initialCtx.graphName(), initialCtx.basedir()));
        this.warningFacts = new BuildWarningFacts(sessionStore.dsl(),
            new GraphIdentity(initialCtx.graphName(), initialCtx.basedir()));
        // No graph identity: a .java file's declarations are facts about the file, and a file
        // belongs to whoever compiles it rather than to a graph.
        this.javaSourceFacts = new JavaSourceFacts(sessionStore.dsl());

        // Vocabulary-less until the store arrives on the next line: the directive vocabulary is
        // read out of the session's graph now, so there is nothing to hand the constructor.
        var workspace = new Workspace();
        // The editor's read access to the store, connections of its own rather than a share of the
        // writer's: LSP requests arrive concurrently while a capture round holds this handle. Three
        // readers rather than one because reads on one reader serialize and the session has three
        // things waiting on them, so one reader would make a keystroke queue behind a whole-workspace
        // drain, or behind a whole-file inlay request, for no better reason than sharing a
        // connection. The annotation reader takes the interactive budget rather than one of its own:
        // what the split changes is which queue an inlay request waits in, and a second constant
        // holding the same number would invite tuning it into the latency policy the budgets are
        // deliberately not. All three live with the workspace and are closed with it in cleanup().
        this.lspStore = new StoreAccess(
            sessionStore.reader(INTERACTIVE_READ_BUDGET),
            sessionStore.reader(INTERACTIVE_READ_BUDGET),
            sessionStore.reader(SESSION_READ_BUDGET),
            initialCtx.graphName());
        workspace.setStore(lspStore);
        // The MCP server's reader, minted from the same call for the same reason one connection cannot
        // carry two transactions: the language server's reads and an MCP tool's would otherwise
        // serialize behind each other for no better cause than sharing a socket.
        this.mcpStore = sessionStore.reader(MCP_READ_BUDGET);
        if (initial.classified()) {
            // The round classified, so it has findings worth replaying. The facts go in before the
            // enqueue: a recalculation replays what this round wrote about each open file.
            writeReportFacts(initial.walkErrors(), initial.warnings());
            workspace.markAllForRecalculation();
        }
        // Build the debounce and save-listener before bindServer so DevServer
        // can hand the listener to each editor-facing GraphitronLanguageServer.
        // LSP didSave fires this listener on the same debounce window the
        // filesystem watcher uses, so the two paths coalesce on a single regen.
        this.schemaDebounce = new DebounceExecutor(debounceMs);
        Consumer<String> saveListener = buildSaveListener(
            initialCtx.schemaFileExtensions(), schemaDebounce, () -> regenerate(workspace));
        try {
            bindServer(workspace, saveListener,
                new RagConfig(resolveRagCacheDirectory(initialCtx.basedir())),
                buildExecuteToolConfig(initialCtx),
                new StoreHandle(sessionStore.dsl(), initialCtx.graphName()), mcpStore,
                coordinatesOf(storeConsoleHandle));
        } catch (MojoExecutionException e) {
            // A bind failure is the one path out of this method that never reaches cleanup(), so the
            // console it opened above has to be given back here or its port stays held for the rest
            // of the Maven run. Same reason bindServer unwinds the warms itself: this returns into a
            // still-live JVM rather than an exiting one.
            if (storeConsoleHandle != null) {
                storeConsoleHandle.close();
            }
            throw e;
        }
        // Seed the source facts so goto-definition / hover work before the first .java edit; the
        // source watcher refreshes them on the source cadence thereafter. Path-only read on
        // initialCtx (no loader).
        refreshSourceFacts(initialCtx, false);
        // Diagnostic so a "completion works but goto-definition returns nothing"
        // report can be traced to a module whose classes are scanned but whose
        // source root is not walked: the two counts should track each other.
        getLog().info("graphitron:dev: scanning " + initialCtx.classpathRoots().size()
            + " reactor classpath root(s), " + initialCtx.compileSourceRoots().size()
            + " source root(s); " + initial.catalog().externalReferences().size()
            + " external reference(s) indexed");
        // Self-explain the single-module-reactor case the sibling walk-up could not
        // widen: when graphitron:dev runs from inside a sub-module and no ancestor pom lists
        // it (so no siblings were resolved), only this module's target/classes is scanned.
        // Without this line the symptom is a silent empty popup with nothing to grep for.
        if (singleProjectReactor() && siblingModuleBasedirs().isEmpty()) {
            getLog().info("graphitron:dev: this reactor resolved to a single module and no "
                + "sibling modules were found to scan. If services / conditions / records "
                + "live in sibling modules, run from the aggregator (e.g. mvn -pl <module> "
                + "graphitron:dev) or check that the parent pom's <modules> lists this module.");
        }
        // Name any module the auto-include could not close: scanned for completion
        // (its target/classes is on disk) but contributing no walked source root, so
        // goto-definition / hover on its declarations is a silent no-jump. The
        // common residue is a table arriving only as a dependency JAR with no .java.
        var unwalked = unwalkedScannedModules();
        if (!unwalked.isEmpty()) {
            getLog().warn("graphitron:dev: " + unwalked.size()
                + " scanned reactor module(s) contribute no walked source root, so "
                + "goto-definition / hover on their declarations returns nothing: "
                + String.join(", ", unwalked)
                + ". Build a module to put its generated sources on disk; a table that "
                + "arrives only as a dependency JAR has no source to walk.");
        }
        // Build the warm compiler and compile the whole generated tree before the watchers start, so
        // the exclusive dir holds a complete runnable image the MCP query tools can execute against
        // from the first edit. Must precede the classpath watcher: its rebuildCatalog callback drives
        // the consumer-change recompile off this same driver.
        maybeStartIncrementalCompiler();
        Set<Path> schemaRoots = startSchemaWatcher(initialCtx, workspace);
        startClasspathWatcher(initialCtx, workspace);
        startSourceWatcher(initialCtx);

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
        ExecuteTool.Config executeConfig, StoreHandle storeHandle, StoreReader storeReader,
        StoreConsole.Coordinates consoleCoordinates)
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
        // Quiet the RAG warms' non-actionable startup log noise before the warms start. The
        // warms load the noisy classes (DJL tokenizer, Lucene VectorizationProvider) on their
        // graphitron-warm-* daemon threads, and thread-start is a happens-before edge, so establishing
        // the suppression on this dev thread first guarantees it is visible when those threads touch
        // the loggers. Do not reorder this after the start() calls below. The helper lives in
        // graphitron-mcp because the logger names are facts about the quarantined RAG dependency set.
        RagLogQuieting.quietRagWarmLogs(getLog()::info);
        // Start the RAG warms before binding the MCP server so they warm during startup; both run on
        // their own daemon threads and never block the bind. Both come from the injectable factories
        // (see the field comment), so the heavy ONNX load stays out of the fast test suite; a
        // factory returning null runs structured-only.
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
                new InetSocketAddress(LOOPBACK_HOST, mcpPort), embedderWarm, docsWarm, ragConfig,
                executeConfig, storeHandle, storeReader, consoleCoordinates);
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
     * Opens the session's fact-store console, or says in the log how to open one.
     *
     * <p>Both arms print a whole command, which is the point rather than a nicety. The port is
     * ephemeral by default, so a line carrying a placeholder would be useless: the log is the only
     * place that value exists. And the disabled arm is the default, so its line is the one most
     * developers will actually meet, which is why it names the command that starts a console rather
     * than leaving that to the manual.
     *
     * <p>The console is a debug affordance, so a failure to open degrades: it warns with the reason
     * and the session continues without one, which is {@code openAt}'s posture that trouble in a
     * convenience costs the convenience and never correctness.
     */
    StoreConsole startStoreConsole() throws MojoExecutionException {
        Integer port = resolveStoreConsole();
        if (port == null) {
            getLog().info("graphitron:dev: no fact-store console (<storeConsole> or "
                + "GRAPHITRON_DEV_STORE_CONSOLE). To query");
            getLog().info("graphitron:dev: this session's facts with psql, restart with:");
            // The same string the MCP store.console tool hands an agent, so the line a developer
            // reads and the line an agent quotes cannot drift into two spellings.
            getLog().info("graphitron:dev:   " + GraphitronMcpServer.ENABLE_STORE_CONSOLE);
            return null;
        }
        try {
            StoreConsole console = sessionStore.console(port);
            getLog().info("graphitron:dev: fact-store console up, read-only, "
                + console.relationCount() + " relations linked, " + LOOPBACK_HOST + " only.");
            getLog().info("graphitron:dev:   " + console.connectCommand());
            return console;
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: no fact-store console, the session continues without "
                + "one: " + e.getMessage());
            // The remedy for the one failure this goal cannot prevent. H2 reads h2.bindAddress when
            // its first class initialises, which in a JVM that already opened a store is before this
            // goal ran at all; putting it on the Maven command line is what gets it there in time.
            getLog().warn("graphitron:dev:   if the listener could not be confined, restart with "
                + "MAVEN_OPTS=-Dh2.bindAddress=" + LOOPBACK_HOST + " so H2 reads it before it loads.");
            return null;
        }
    }

    /**
     * Reconciles the {@code <storeConsole>} block with its environment overrides, env winning per
     * field: the port to bind ({@code 0} for an ephemeral one, which is the default and the
     * encouraged shape), or {@code null} where no console was asked for.
     *
     * <p>{@code null} rather than a disabled-carrying record because absence is the whole answer at
     * this level: no console, no port bound, nothing else configured. A port that will not parse
     * fails the goal loudly rather than falling back to ephemeral, on the same grounds as an
     * unsupported dev-database dialect: a value the developer typed and this goal ignored is worse
     * than a stop.
     */
    Integer resolveStoreConsole() throws MojoExecutionException {
        String enabled = environment.get("GRAPHITRON_DEV_STORE_CONSOLE");
        boolean on = enabled != null && !enabled.isBlank()
            ? Boolean.parseBoolean(enabled.strip())
            : storeConsole != null && Boolean.TRUE.equals(storeConsole.enabled);
        if (!on) {
            return null;
        }
        String port = firstNonBlank(environment.get("GRAPHITRON_DEV_STORE_CONSOLE_PORT"),
            storeConsole == null || storeConsole.port == null ? null : storeConsole.port.toString());
        if (port == null) {
            return 0;
        }
        try {
            return Integer.parseInt(port.strip());
        } catch (NumberFormatException e) {
            throw new MojoExecutionException(
                "graphitron:dev: '" + port + "' is not a port number. Set <storeConsole><port> or "
                    + "GRAPHITRON_DEV_STORE_CONSOLE_PORT to an integer, or leave it unset for an "
                    + "ephemeral port, which is the encouraged shape.", e);
        }
    }

    /**
     * The console's coordinates, or {@code null} where there is no console. Null-safe here rather
     * than at each site that threads them on, an agent-facing tool that answers {@code disabled}
     * being exactly what the absent case means.
     */
    static StoreConsole.Coordinates coordinatesOf(StoreConsole console) {
        return console == null ? null : console.coordinates();
    }

    /**
     * Reconciles the {@code <devDatabase>} block with its environment overrides into the
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
        var paths = new LinkedHashSet<Path>();
        for (var entry : resolveCompileClasspath()) {
            paths.add(entry.path());
        }
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
     * Starts the {@code .java} source-root watcher. Walks the same
     * compile source roots the catalog build uses, but on the source cadence:
     * a hand-edited service / condition source moves its declaration rows in the
     * store's {@code java_} family without waiting for a {@code .class} rebuild. The
     * walk is parse-only (no reflection, no classpath resolution), so it runs
     * straight off the captured {@code ctx}'s path fields without a codegen
     * scope, unlike the regenerate / rebuildCatalog triggers.
     */
    private void startSourceWatcher(RewriteContext ctx) throws MojoExecutionException {
        Set<Path> roots = resolveSourceRoots(ctx);
        if (roots.isEmpty()) {
            getLog().info("graphitron:dev: skipping source watcher; "
                + "no compile source roots resolved (goto-definition positions stay at startup walk)");
            return;
        }
        this.sourceDebounce = new DebounceExecutor(debounceMs);
        try {
            this.sourceWatcher = new SchemaWatcher(
                roots, sourceDebounce, () -> refreshSourceFacts(ctx, true), ".java");
        } catch (IOException e) {
            cleanup();
            throw new MojoExecutionException(
                "graphitron:dev: failed to start source watcher", e);
        }
        Thread sourceThread = new Thread(sourceWatcher::run, "graphitron-dev-source");
        sourceThread.setDaemon(true);
        sourceThread.start();
    }

    /**
     * One parse of the changed sources into the store's {@code java_} family, refreshed file by
     * file. That family is the standing record every reader asks, so there is one sink and no
     * second read of the same file to disagree with it.
     *
     * @param announce whether to say so on the console; the watcher's refresh is news, the startup
     *                 seed is not, the line after it already reporting how many roots were walked
     */
    private void refreshSourceFacts(RewriteContext ctx, boolean announce) {
        try {
            var walk = sourceWalker.walkFiles(ctx.compileSourceRoots());
            if (javaSourceFacts != null) {
                javaSourceFacts.refresh(ctx.compileSourceRoots(), walk);
            }
            if (announce) {
                getLog().info(
                    "graphitron:dev: source change detected; refreshed goto-definition positions");
            }
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: source-position refresh failed; keeping previous: "
                + e.getMessage());
        }
    }

    private void regenerate(Workspace workspace) {
        try {
            withCodegenScope(ctx -> regeneratePass(ctx, workspace));
        } catch (MojoExecutionException e) {
            getLog().error("graphitron:dev: failed to rebuild context", e);
            workspace.markAllForRecalculation();
        }
    }

    /**
     * One save's round, inside a live codegen scope: one generator pass, whose emitted half feeds
     * the incremental recompile and whose reporting half is published to the store's diagnostics
     * stratum and replayed to every open file. One pass rather than two, so the graph's partition
     * is written once and the round's tree and its facts describe the same read of the schema.
     *
     * <p>Package-private so {@code DevMojoTest} can drive a whole round against a context it builds
     * itself: the scope around this is the mojo's classloader plumbing, not anything the round
     * decides.
     */
    void regeneratePass(RewriteContext ctx, Workspace workspace) {
        getLog().info(banner("regenerate"));
        var round = runGeneratorPass(ctx, "regenerate");
        // A clean regen produces the writer's delta + this schema's compile graph; recompile
        // just the affected sub-closure into the exclusive dir. A failed regen leaves the last
        // good .class in place (nothing to recompile from), matching the generate-only path.
        if (round.generated() && incrementalCompiler != null && lastGeneration != null) {
            var gen = lastGeneration;
            var outcome = incrementalCompiler.recompile(
                gen.result().emittedUnits(), gen.result().changedUnits(), gen.graph());
            reportCompile(outcome, "recompile");
        }
        // Null before startSchemaWatcher has run, which an editor save arriving over the LSP can
        // beat: the save listener is wired at bindServer, a few lines earlier. Nothing to register
        // a new root with yet, and the watcher resolves its own roots when it starts.
        if (schemaWatcher != null) {
            for (Path root : resolveSchemaRoots(ctx)) {
                try {
                    schemaWatcher.addRoot(root);
                } catch (IOException e) {
                    getLog().warn("graphitron:dev: failed to register new watch root "
                        + root + ": " + e.getMessage());
                }
            }
        }
        // The round's own diagnostics, from the pass that produced them, whether or not it emitted:
        // a validation-rejected round has a report and publishes it. A read that refused has none,
        // having written its own verdict to the store on the way through, so the stratum keeps the
        // last good round's rows and the recalculation below replays that verdict either way.
        if (round.output() != null) {
            writeReportFacts(round.output().walkErrors(), round.output().warnings());
        }
        workspace.markAllForRecalculation();
    }

    private void rebuildCatalog(Workspace workspace) {
        getLog().info("graphitron:dev: classpath change detected; rebuilding catalog");
        try {
            withCodegenScope(ctx -> {
                try {
                    var output = new GraphQLRewriteGenerator(ctx, captureFor(ctx)).buildOutput();
                    writeReportFacts(output.walkErrors(), output.warnings());
                    workspace.markAllForRecalculation();
                    var catalog = output.catalog();
                    getLog().info("graphitron:dev: catalog refreshed (" + catalog.tables().size()
                        + " tables, " + catalog.types().size() + " scalars)");
                    // A consumer .class changed: a generated unit that compiles against it may now be
                    // stale. The compile graph carries only generated→generated edges (no
                    // generated→consumer edge to walk), so invalidate conservatively by recompiling the
                    // whole cached generated tree.
                    if (incrementalCompiler != null && lastGeneration != null) {
                        var outcome = incrementalCompiler.compileAll(lastGeneration.result().emittedUnits());
                        reportCompile(outcome, "recompile (consumer classpath change)");
                    }
                } catch (RuntimeException e) {
                    // Bad schema mid-edit: keep the previous catalog so completions do not
                    // silently disappear, and republish diagnostics, the refused read having
                    // written its own verdict. The next save will re-trigger.
                    getLog().warn("graphitron:dev: catalog rebuild failed; keeping previous: "
                        + e.getMessage());
                    workspace.markAllForRecalculation();
                }
            });
        } catch (MojoExecutionException e) {
            getLog().error("graphitron:dev: catalog rebuild failed (context)", e);
        }
    }

    /**
     * Initial catalog for a startup that emits nothing: the {@code -Dgraphitron.dev.skipInitial}
     * arm, where there is no pass to read the editor's products off. A generating startup takes
     * {@link InitialOutput#of} over its own pass instead.
     *
     * <p>A schema parse or classification failure is surfaced as a warning and an empty catalog that
     * did not classify: the LSP must still come up so the developer can fix the schema, and the
     * schema watcher will re-build on the next save.
     */
    private InitialOutput buildOutputQuietly(RewriteContext ctx) {
        try {
            var output = new GraphQLRewriteGenerator(ctx, captureFor(ctx)).buildOutput();
            return new InitialOutput(output.catalog(), true, output.walkErrors(), output.warnings());
        } catch (RuntimeException e) {
            getLog().warn("graphitron:dev: initial catalog build failed; "
                + "starting with empty catalog: " + e.getMessage());
            return new InitialOutput(CompletionData.empty(), false, List.of(), List.of());
        }
    }

    /**
     * Carrier for {@link #buildOutputQuietly}'s output. {@code classified} says whether the round
     * got as far as classifying, which is the whole of what the caller needs to know: a round that
     * threw has no findings to publish and nothing to enqueue. The two lists are the
     * diagnostics-stratum loaders' input, published only on that path, so a failed build writes
     * nothing and the store keeps the last good round's rows rather than an empty partition that
     * would read as a clean schema. The assembled report does not ride along, the language server
     * reading a build's own findings from the store those lists are written to.
     */
    private record InitialOutput(CompletionData catalog, boolean classified,
                                 List<no.sikt.graphitron.rewrite.ValidationError> walkErrors,
                                 List<no.sikt.graphitron.rewrite.BuildWarning> warnings) {

        /**
         * The same carrier read off a generating startup's own pass, so the initial run's products
         * come from the round that emitted rather than from a second read of the same files. A
         * round with no output failed before classifying and starts the session with an empty
         * catalog, exactly as {@link DevMojo#buildOutputQuietly} does on the {@code skipInitial}
         * path.
         */
        static InitialOutput of(PassRound round) {
            return round.output() == null
                ? new InitialOutput(CompletionData.empty(), false, List.of(), List.of())
                : new InitialOutput(round.output().catalog(), true,
                    round.output().walkErrors(), round.output().warnings());
        }
    }

    /**
     * Publishes one build's schema-side diagnostics into the fact store beside the workspace
     * publication: the walk's error stream to the rejection residue, the suppression-filtered
     * warning list to the lint and advisory arms. Same handle and cadence as
     * {@link CompileFacts}; called only where the build produced output, never on a failure
     * path, so a broken build keeps the previous snapshot's rows instead of writing an empty
     * partition that would read as a clean schema.
     */
    private void writeReportFacts(List<no.sikt.graphitron.rewrite.ValidationError> walkErrors,
                                  List<no.sikt.graphitron.rewrite.BuildWarning> warnings) {
        rejectionFacts.write(walkErrors);
        warningFacts.write(warnings);
    }

    /**
     * One generator pass's outcome as the dev loop reads it: the round's editor-facing output, or
     * {@code null} where the pass failed before it could classify and so has nothing to publish;
     * and whether sources were emitted, which is what decides a recompile.
     *
     * <p>A validation-rejected round is a third case rather than a failure with no products: it
     * carries an output (catalog and diagnostics, so the editor can autocomplete its way out of the
     * error) and did not emit.
     */
    record PassRound(GraphQLRewriteGenerator.BuildOutput output, boolean generated) {}

    // Package-private so DevMojoTest can drive the catch-arm discrimination directly
    // (a malformed schema vs a missing file) without standing up the full watch loop.
    /**
     * The port a pass captures through: the session's, so every round writes into the store the
     * session's readers are on. A per-pass port where there is no session, which is the unit tier
     * driving {@link #runGeneratorPass} directly without {@link #execute()} having opened one.
     */
    private CapturePort captureFor(RewriteContext ctx) {
        return sessionCapture != null ? sessionCapture : CapturePort.forContext(ctx);
    }

    PassRound runGeneratorPass(RewriteContext ctx, String label) {
        // Cleared up front so a failed pass never leaves a stale generation for the compile driver to
        // act on; reassigned from the pass below, which returns one only when it emitted.
        this.lastGeneration = null;
        try {
            // One pass: the emitted tree, the compile graph the incremental driver reads, and the
            // editor-facing catalog and diagnostics, from a single read of the schema and a single
            // capture of the graph's partition.
            var pass = new GraphQLRewriteGenerator(ctx, captureFor(ctx)).runPass();
            this.lastGeneration = pass.generation().orElse(null);
            var errors = pass.output().report().errors();
            if (!errors.isEmpty()) {
                // A branch rather than a catch arm: the pass that refused to emit is the same pass
                // that produced the output above, so there is nothing to unwind.
                String tree = WatchErrorFormatter.format(errors, previousErrorKeys);
                previousErrorKeys = WatchErrorFormatter.keysOf(errors);
                getLog().error("graphitron:dev: " + label + " failed validation\n" + tree);
                return new PassRound(pass.output(), false);
            }
            previousErrorKeys = Set.of();
            getLog().info("graphitron:dev: " + label + " ok");
            return new PassRound(pass.output(), true);
        } catch (SchemaParseException e) {
            // An invalid intermediate schema mid-edit is expected and author-correctable;
            // surface the attributed file:line:col one-liner without the throwable, so the
            // dev log shows one clean line instead of the graphql-java + executor stack.
            // Not a validator verdict, so it must not feed WatchErrorFormatter's delta
            // tracker: reset so the next successful validation reports its full error set.
            previousErrorKeys = null;
            getLog().error("graphitron:dev: " + label + " failed: " + e.getMessage());
            return new PassRound(null, false);
        } catch (RuntimeException e) {
            previousErrorKeys = null;
            getLog().error("graphitron:dev: " + label + " failed (infrastructure)", e);
            return new PassRound(null, false);
        }
    }

    /**
     * Builds the warm incremental compile driver and compiles the whole generated tree once, unless
     * {@code -Dgraphitron.dev.compile=false} opts out. No fail-fast: if the driver cannot be built (a
     * JRE with no system compiler, or a classpath-assembly failure), it degrades to generate-only for
     * the session with a warning rather than aborting the dev loop. Called once at startup, before the
     * watchers, so the exclusive dir holds a complete image and the consumer-change path has a driver.
     */
    void maybeStartIncrementalCompiler() {
        if (!compile) {
            getLog().info("graphitron:dev: -Dgraphitron.dev.compile=false; "
                + "generating without compiling (in-process query tools unavailable)");
            return;
        }
        Path classesDir = resolveGraphitronClassesDirectory(project.getBasedir().toPath());
        try {
            this.incrementalCompiler = new IncrementalCompiler(classesDir,
                resolveCompileClasspath().stream().map(no.sikt.graphitron.rewrite.ClasspathEntry::path).toList());
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
            reportCompile(outcome, "initial compile");
        } else {
            // No initial generation happened this startup (skipInitial, or the initial pass failed), so
            // there are no in-memory TypeSpecs to compile the whole tree from. The exclusive dir fills in
            // incrementally from the first save's recompile rather than as a complete image up front.
            getLog().info("graphitron:dev: no initial generation; the generated-class image fills in "
                + "from the first save (skip -Dgraphitron.dev.skipInitial to compile the whole tree at startup)");
        }
    }

    /**
     * Surfaces one compile round through two channels: the console (a labelled
     * generated-code block on failure via {@link CompileErrorFormatter}, a one-line summary on success),
     * and the fact store's {@code javac_diagnostic} relation (via
     * {@link CompileFacts}, on the session's own store handle so store-side readers see the round the
     * moment it is written). The round's full diagnostic list is published even on success so a prior
     * failure is cleared once it resolves. Every reader of a compile round reads the store, the
     * language server included, so there is no in-memory slot to keep in step with the relation.
     */
    void reportCompile(CompileOutcome outcome, String label) {
        var round = outcome.round();
        if (compileFacts != null) {
            compileFacts.write(round);
        }
        if (round.success()) {
            getLog().info("graphitron:dev: " + label + " compiled "
                + outcome.compiledUnits().size() + " unit(s) ok");
        } else {
            getLog().error("graphitron:dev: " + label + "\n"
                + CompileErrorFormatter.format(round.errors()));
        }
    }

    // Package-private so DevMojoTest can assert that a session's own listeners and handles are
    // released rather than left in the JVM; every member it touches is null-guarded, so a mojo
    // holding only some of them tears down cleanly.
    void cleanup() {
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
        // Before the store, and before the readers: the console holds a link connection pointing at
        // the store, so it is the first of the store's dependants to go.
        if (storeConsoleHandle != null) {
            storeConsoleHandle.close();
        }
        // Before the store itself, since both read through a connection the store minted.
        if (lspStore != null) {
            lspStore.close();
        }
        if (mcpStore != null) {
            mcpStore.close();
        }
        // Before the store, and after the servers: the capture port. Ordinarily this gives back
        // nothing, the port having been lent the store below rather than opening one. It has
        // something to give back only where a refusal demoted it to a private store of its own,
        // and that store is the port's to close.
        if (sessionCapture != null) {
            sessionCapture.close();
        }
        // Last, after the servers whose tools read through it: the session's store handle. A
        // file-backed store only releases its connection here; the file stays for the next run.
        if (sessionStore != null) {
            sessionStore.close();
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

    /**
     * The directories the watcher watches: each loaded schema file's parent. Read off the source's
     * file arm rather than reconstructed from its name, so nothing here re-derives path-ness from a
     * string the producer already classified. A label has no directory to watch.
     */
    private static Set<Path> resolveSchemaRoots(RewriteContext ctx) {
        Set<Path> roots = new LinkedHashSet<>();
        for (var input : ctx.schemaInputs()) {
            switch (input.source()) {
                case SchemaSource.File file -> {
                    Path parent = file.path().getParent();
                    if (parent != null) {
                        roots.add(parent);
                    }
                }
                case SchemaSource.Named ignored -> { }
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
        for (var entry : ctx.classpathRoots()) {
            Path root = entry.path();
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
