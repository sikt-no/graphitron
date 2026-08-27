package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.FactWriters;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;
import no.sikt.graphitron.rewrite.compile.CompileOutcome;
import no.sikt.graphitron.rewrite.compile.CompileRound;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.mcp.GraphitronMcpServer;
import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.EmbeddingStore;
import no.sikt.graphitron.mcp.rag.WarmState;
import no.sikt.graphitron.mcp.rag.docs.DocsIndex;
import no.sikt.graphitron.rewrite.maven.dev.DevServer;
import no.sikt.graphitron.rewrite.maven.watch.DebounceExecutor;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mojo-level configuration coverage. The full {@code execute()} loop is
 * blocking by design (Ctrl+C is the exit), so it is not driven from
 * tests; {@link no.sikt.graphitron.rewrite.maven.dev.DevServerTest}
 * covers the socket behaviour directly. This test focuses on the bits
 * the Mojo owns that {@code DevServer} doesn't see: the bind-failure
 * message contract, and the override-property defaults.
 */
class DevMojoTest {

    @Test
    void defaultsMatchPlanContract() {
        // The literals here lock the user-facing design constants from
        // plan-graphitron-lsp.md (port 8487, loopback bind) and the MCP port (8488).
        // This pins the Java source of truth for the MCP port so it cannot silently drift
        // from the design; the static copies (.mcp.json, docs) are accepted drift.
        assertThat(DevMojo.DEFAULT_PORT).isEqualTo(8487);
        assertThat(DevMojo.DEFAULT_MCP_PORT).isEqualTo(8488);
        assertThat(DevMojo.LOOPBACK_HOST).isEqualTo("127.0.0.1");
    }

    @Test
    void bindToTakenPortFailsWithOverrideHint(@TempDir Path basedir) throws Exception {
        // Occupy a port so the Mojo's bind path hits BindException.
        try (var blocker = new DevServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            new no.sikt.graphitron.lsp.state.Workspace(),
            uri -> {})) {
            int taken = blocker.port();

            var mojo = mojoFor(basedir, taken, DevMojo.DEFAULT_MCP_PORT);

            assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining(String.valueOf(taken))
                .hasMessageContaining("-Dgraphitron.dev.port=N");
        }
    }

    @Test
    void mcpBindFailureSurfacesMojoMessageAndClosesLspSocket(@TempDir Path basedir) throws Exception {
        // The LSP binds a free ephemeral port (port 0) and succeeds; the MCP bind then lands on a
        // port already held by another graphitron:dev session. The Mojo must surface a
        // MojoExecutionException naming the MCP port, and must close the already-bound LSP socket
        // so the partial startup leaks nothing. This pins the failure-contract parity with the LSP
        // bind: the user-visible MojoExecutionException, not the
        // server-level IOException GraphitronMcpServerTest covers.
        try (var mcpBlocker = new GraphitronMcpServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            int takenMcpPort = mcpBlocker.port();

            var mojo = mojoFor(basedir, 0, takenMcpPort);
            // A console opened before the bind, so the unwind's reach over it can be asserted below.
            mojo.environment = Map.of("GRAPHITRON_DEV_STORE_CONSOLE", "true");
            // Inject ONNX-free warms the unwind can be observed against: the embedder loader returns
            // nothing (no real BgeEmbedder ONNX load, which would SIGSEGV the fork), and the docs warm
            // wraps a store whose close() the bind-failure unwind must call. Started during bind, these
            // are the warms started-above the failing MCP bind must tear down.
            var spyStore = new ClosingSpyStore();
            var embedderWarm = new AsyncWarm<Embedder>("test-embedder", () -> null);
            var docsWarm = new AsyncWarm<DocsIndex>("test-docs", () -> new DocsIndex(spyStore, 3));
            mojo.embedderWarmFactory = () -> embedderWarm;
            mojo.docsWarmFactory = () -> docsWarm;

            assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining("MCP port")
                .hasMessageContaining(String.valueOf(takenMcpPort));

            assertThat(mojo.server)
                .as("the LSP DevServer was constructed before the MCP bind failed")
                .isNotNull();
            assertThat(mojo.server.isClosed())
                .as("partial bind unwound: the LSP socket must be closed, not leaked")
                .isTrue();
            // The warms started above must be unwound too, not just the LSP socket. Joining them on
            // the failure path means no warm daemon outlives the unwind (the SIGSEGV's proximate
            // cause), and the warmed docs store must be closed so the in-memory index is freed.
            assertThat(mojo.embedderWarm.state())
                .as("the embedder warm was joined on the unwind, not left running")
                .isNotInstanceOf(WarmState.Warming.class);
            assertThat(mojo.docsWarm.state())
                .as("the docs warm was joined on the unwind, not left running")
                .isNotInstanceOf(WarmState.Warming.class);
            assertThat(spyStore.closed)
                .as("the bind-failure unwind closed the warmed docs store")
                .isTrue();
            // The fact-store console opened before the bind, and this path never reaches cleanup(),
            // so it is the unwind's to give back. It matters more than it looks under mvnd, whose
            // daemon JVM outlives the failed build and would keep the port held.
            assertThat(mojo.storeConsoleHandle)
                .as("the console opened before the MCP bind was attempted")
                .isNotNull();
            assertThat(mojo.storeConsoleHandle.running())
                .as("the bind-failure unwind stopped the console's listener too")
                .isFalse();
        }
    }

    /** An {@link EmbeddingStore} that records its close, so a test can assert the unwind freed it. */
    private static final class ClosingSpyStore implements EmbeddingStore {
        volatile boolean closed = false;

        @Override
        public void add(String id, Embedder.Embedding embedding, String payload) {}

        @Override
        public List<EmbeddingStore.Hit> search(Embedder.Query query, int k) {
            return List.of();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @Test
    void saveListener_schemaSuffixSchedulesRegen() throws Exception {
        // Listener filters by suffix and schedules through the debounce. A
        // .graphqls URI schedules a regen run; a .md URI is dropped before
        // the debounce sees it.
        AtomicInteger regens = new AtomicInteger();
        try (var debounce = new DebounceExecutor(20)) {
            Consumer<String> listener = DevMojo.buildSaveListener(
                Set.of(".graphqls", ".graphql"),
                debounce,
                regens::incrementAndGet);

            listener.accept("file:///path/to/schema.graphqls");
            listener.accept("file:///readme.md");

            Thread.sleep(150);
            assertThat(regens).hasValue(1);
        }
    }

    @Test
    void runGeneratorPass_malformedSchema_logsAttributedLineWithoutThrowable(@TempDir Path basedir) throws Exception {
        // The bug this item fixes: a half-edited (syntactically invalid) schema dumped
        // a ~30-frame infrastructure stack trace into the dev log on every keystroke.
        // The parse arm now logs the attributed one-liner WITHOUT the throwable.
        Path broken = basedir.resolve("broken.graphqls");
        Files.writeString(broken, "type Query { films: [Film] }\nstrayTokenHere\n");

        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        var round = mojo.runGeneratorPass(contextFor(basedir, broken), "regen");

        assertThat(round.generated()).isFalse();
        assertThat(round.output())
            .as("a read that refused produces no report, so the store keeps the last good round's rows")
            .isNull();
        assertThat(log.errorThrowables)
            .as("parse failure rides the clean surface: no stack trace logged")
            .isEmpty();
        assertThat(log.errors)
            .anySatisfy(line -> assertThat(line)
                .contains("regen failed: ")
                .contains("Schema parse failed in")
                .contains(broken.toString()));
    }

    @Test
    void runGeneratorPass_missingSchema_takesInfrastructureArmWithThrowable(@TempDir Path basedir) {
        // A missing / unreadable file is a bare RuntimeException, not a SchemaParseException,
        // so the generic infrastructure arm runs and keeps logging WITH the throwable. This
        // pins the catch-arm ordering: were the parse arm catching too broadly, no throwable
        // would reach the log here.
        Path missing = basedir.resolve("nope-missing.graphqls");

        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        var round = mojo.runGeneratorPass(contextFor(basedir, missing), "regen");

        assertThat(round.generated()).isFalse();
        assertThat(log.errorThrowables)
            .as("genuine infrastructure failure keeps its diagnostic stack trace")
            .isNotEmpty();
        assertThat(log.errors)
            .anySatisfy(line -> assertThat(line).contains("failed (infrastructure)"));
    }

    /**
     * One save's whole round, through the merged pass: the generator runs once and its reporting
     * half is published to the store's diagnostics stratum and replayed to every open file. Before
     * the merge these were two generator calls, the second existing only to produce the facts this
     * asserts; a round that stopped publishing them would have gone unnoticed here.
     *
     * <p>The fixture is validation-rejected on purpose, because that is the interesting round: the
     * pass declines to emit and still owes the editor a report, so both writers have something to
     * write. The unresolvable {@code @reference} key is the rejection; the snake_case SDL field name
     * is a lint finding beside it.
     */
    @Test
    void regeneratePass_publishesTheRoundsFactsAndReplaysThemToEveryOpenFile(@TempDir Path basedir)
            throws Exception {
        Path schema = basedir.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "no_such_fk"}])
              original_language_id: Int
            }
            type Query { film: Film }
            """);
        String uri = schema.toUri().toString();

        try (var store = FactStores.inMemory()) {
            var mojo = new DevMojo();
            var log = new CapturingLog();
            mojo.setLog(log);
            mojo.sessionStore = store;
            mojo.rejectionFacts = FactWriters.rejectionFacts(store.dsl(), "DevMojoTest", basedir);
            mojo.warningFacts = FactWriters.buildWarningFacts(store.dsl(), "DevMojoTest", basedir);

            var workspace = new Workspace();
            workspace.didOpen(uri, 1, Files.readString(schema));
            workspace.drainRecalculate();

            mojo.regeneratePass(jooqContextFor(basedir, schema), workspace);

            assertThat(store.dsl().fetchCount(no.sikt.graphitron.model.Tables.REJECTION_VALIDATION_ERROR))
                .as("the round's rejection residue reaches the stratum the language server reads")
                .isPositive();
            assertThat(store.dsl().fetchCount(no.sikt.graphitron.model.Tables.LINT_FINDING))
                .as("and so does its post-suppression warning list")
                .isPositive();
            assertThat(workspace.drainRecalculate())
                .as("every open file is re-asked, so the squiggles come from this round")
                .contains(uri);
            assertThat(log.errors)
                .as("one message for the round, the grouped tree rather than a line per error")
                .anySatisfy(line -> assertThat(line).contains("regenerate failed validation"));
        }
    }

    @Test
    void compileOptOut_leavesTheCompilerUnbuiltAndTouchesNoOutputDir(@TempDir Path basedir) {
        // -Dgraphitron.dev.compile=false is the generate-only fall-back: the driver is never built and
        // the graphitron-exclusive output dir is never created. No fail-fast, nothing to corrupt.
        var mojo = new DevMojo();
        var project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        mojo.project = project;
        mojo.compile = false;
        mojo.setLog(new CapturingLog());

        mojo.maybeStartIncrementalCompiler();

        assertThat(mojo.incrementalCompiler)
            .as("compile opt-out: no incremental compile driver is built")
            .isNull();
        assertThat(Files.exists(basedir.resolve("target/graphitron-classes")))
            .as("compile opt-out: the exclusive output dir is never created")
            .isFalse();
    }

    @Test
    void reportCompile_failure_rendersTheConsoleBlock() {
        // The console channel: a labelled generated-code block naming the offending file. The other
        // channel is the fact store, covered below; there is no in-memory one any more.
        var diagnostic = new CompileDiagnostic(
            "gen/pkg/FilmFetchers.java", 12, 7, "ERROR", "compiler.err.cant.resolve", "cannot find symbol");
        var outcome = new CompileOutcome(
            new CompileRound(false, List.of(diagnostic)), Set.of("gen.pkg.FilmFetchers"));
        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        mojo.reportCompile(outcome, "recompile");

        assertThat(log.errors)
            .as("console: a labelled generated-code compile block naming the offending file")
            .anySatisfy(line -> assertThat(line)
                .contains("generated-code compilation failed")
                .contains("gen/pkg/FilmFetchers.java")
                .contains("cannot find symbol"));
    }

    @Test
    void reportCompile_success_logsNoError() {
        var outcome = new CompileOutcome(
            new CompileRound(true, List.of()), Set.of("gen.pkg.A", "gen.pkg.B"));
        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        mojo.reportCompile(outcome, "recompile");

        assertThat(log.errors)
            .as("a clean round logs no error")
            .isEmpty();
    }

    @Test
    void reportCompile_writesTheRoundThroughTheSessionStoreHandle(@TempDir Path basedir) {
        // The other sink: a reported round lands in the fact store's javac_diagnostic relation,
        // readable on the same handle afterwards; that is the delivery guarantee stated rather
        // than assumed (the writer and the store-side readers share the session's live handle).
        // The clean round that follows is where "a prior failure is cleared" lives now that every
        // reader of a compile round reads this relation.
        try (var store = FactStores.inMemory()) {
            var mojo = new DevMojo();
            mojo.setLog(new CapturingLog());
            mojo.compileFacts = FactWriters.compileFacts(store.dsl(), "dev-session", basedir);
            var diagnostic = new CompileDiagnostic(
                "file:///gen/pkg/FilmFetchers.java", 12, 7, "ERROR", "compiler.err.cant.resolve",
                "cannot find symbol");
            var outcome = new CompileOutcome(
                new CompileRound(false, List.of(diagnostic)), Set.of("gen.pkg.FilmFetchers"));

            mojo.reportCompile(outcome, "recompile");

            var rows = store.dsl().selectFrom(no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC).fetch();
            assertThat(rows).hasSize(1);
            assertThat(rows.getFirst().getGraphName()).isEqualTo("dev-session");
            assertThat(rows.getFirst().getFile()).isEqualTo("file:///gen/pkg/FilmFetchers.java");
            assertThat(rows.getFirst().getCode()).isEqualTo("compiler.err.cant.resolve");

            mojo.reportCompile(
                new CompileOutcome(new CompileRound(true, List.of()), Set.of("gen.pkg.FilmFetchers")),
                "recompile");

            assertThat(store.dsl().fetchCount(no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC))
                .as("a clean round clears the prior failure, so no reader shows a stale error")
                .isZero();
        }
    }

    // ---- <devDatabase> reconciliation (env wins over pom; degrade vs fail-loud) ----

    @Test
    void devDatabase_absent_disablesTheExecuteToolQuietly() throws Exception {
        var mojo = new DevMojo();
        mojo.environment = Map.of();
        assertThat(mojo.resolveDevDatabase()).isNull();
    }

    @Test
    void devDatabase_fromPom_resolvesAllFields() throws Exception {
        var mojo = new DevMojo();
        mojo.environment = Map.of();
        mojo.devDatabase = binding("jdbc:postgresql://localhost/dev", "dev", "secret", "postgres",
            "{\"sub\":\"u\"}", true);

        var resolved = mojo.resolveDevDatabase();
        assertThat(resolved.db().url()).isEqualTo("jdbc:postgresql://localhost/dev");
        assertThat(resolved.db().user()).isEqualTo("dev");
        assertThat(resolved.db().password()).isEqualTo("secret");
        // The dialect normalizes to the enumerated upper-case form.
        assertThat(resolved.db().dialect()).isEqualTo("POSTGRES");
        assertThat(resolved.db().claims()).isEqualTo("{\"sub\":\"u\"}");
        assertThat(resolved.allowClaimsOverride()).isTrue();
    }

    @Test
    void devDatabase_envOverridesPomOnEveryField() throws Exception {
        var mojo = new DevMojo();
        mojo.devDatabase = binding("jdbc:pom:url", "pom-user", "pom-pass", "POSTGRES", "pom-claims", true);
        mojo.environment = Map.of(
            "GRAPHITRON_DEV_DB_URL", "jdbc:env:url",
            "GRAPHITRON_DEV_DB_USER", "env-user",
            "GRAPHITRON_DEV_DB_PASSWORD", "env-pass",
            "GRAPHITRON_DEV_DB_DIALECT", "ORACLE",
            "GRAPHITRON_DEV_CLAIMS", "env-claims",
            "GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE", "false");

        var resolved = mojo.resolveDevDatabase();
        assertThat(resolved.db().url()).isEqualTo("jdbc:env:url");
        assertThat(resolved.db().user()).isEqualTo("env-user");
        assertThat(resolved.db().password()).isEqualTo("env-pass");
        assertThat(resolved.db().dialect()).isEqualTo("ORACLE");
        assertThat(resolved.db().claims()).isEqualTo("env-claims");
        assertThat(resolved.allowClaimsOverride()).isFalse();
    }

    @Test
    void devDatabase_envAloneIsEnough_noPomBlockNeeded() throws Exception {
        var mojo = new DevMojo();
        mojo.environment = Map.of(
            "GRAPHITRON_DEV_DB_URL", "jdbc:env:url",
            "GRAPHITRON_DEV_DB_DIALECT", "POSTGRES");

        var resolved = mojo.resolveDevDatabase();
        assertThat(resolved.db().url()).isEqualTo("jdbc:env:url");
        assertThat(resolved.allowClaimsOverride()).isFalse();
    }

    @Test
    void devDatabase_urlWithoutDialect_failsLoud_neverDefaulted() {
        // A half-configured dev database is a config bug, not a degrade case; and the dialect is
        // explicit and enumerated by design (graphitron is multi-dialect, Sikt runs Oracle).
        var mojo = new DevMojo();
        mojo.environment = Map.of("GRAPHITRON_DEV_DB_URL", "jdbc:env:url");
        assertThatThrownBy(mojo::resolveDevDatabase)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("dialect")
            .hasMessageContaining("never defaulted");
    }

    @Test
    void devDatabase_unsupportedDialect_failsLoudNamingTheSupportedSet() {
        var mojo = new DevMojo();
        mojo.environment = Map.of();
        mojo.devDatabase = binding("jdbc:pom:url", null, null, "MYSQL", null, null);
        assertThatThrownBy(mojo::resolveDevDatabase)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("MYSQL")
            .hasMessageContaining("POSTGRES and ORACLE");
    }

    // ---- <storeConsole> reconciliation and the two log lines ----

    /**
     * The port the pinned-port case binds, and it is below the operating system's ephemeral range on
     * purpose. A port reserved by opening and closing a socket goes straight back to the range every
     * other listener in a parallel build is drawing from, and the second or two before the console
     * binds it is enough for something else to take it; a port the operating system never hands out
     * cannot be taken that way. Distinct from the ones graphitron-model's own console cases pin,
     * those forks running beside this one.
     */
    private static final int PINNED_CONSOLE_PORT = 18481;

    /**
     * The disabled path is the default, so its log line is as much a shipped surface as the enabled
     * one: a developer who never reads the manual gets from "I wish I could see the rows" to a psql
     * prompt on what the session told them. The command it names is the one the MCP tool hands an
     * agent, from the same constant.
     */
    @Test
    void storeConsole_absent_bindsNoPortAndSaysHowToStartOne() throws Exception {
        var mojo = new DevMojo();
        var log = new CapturingLog();
        mojo.setLog(log);
        mojo.environment = Map.of();

        assertThat(mojo.resolveStoreConsole()).isNull();
        assertThat(mojo.startStoreConsole()).isNull();
        assertThat(log.infos)
            .anySatisfy(line -> assertThat(line).contains("no fact-store console"))
            .anySatisfy(line -> assertThat(line).contains("psql"))
            .anySatisfy(line -> assertThat(line).endsWith(GraphitronMcpServer.ENABLE_STORE_CONSOLE));
        assertThat(DevMojo.coordinatesOf(null))
            .as("and the MCP server is handed no coordinates, which is what its disabled arm means")
            .isNull();
    }

    @Test
    void storeConsole_enabledWithNoPort_opensOnAnEphemeralPortAndLogsTheWholeCommand() throws Exception {
        try (var store = FactStores.inMemory()) {
            var mojo = new DevMojo();
            var log = new CapturingLog();
            mojo.setLog(log);
            mojo.environment = Map.of("GRAPHITRON_DEV_STORE_CONSOLE", "true");
            mojo.sessionStore = store;

            assertThat(mojo.resolveStoreConsole())
                .as("an unset port means ephemeral, the encouraged shape")
                .isZero();
            var console = mojo.startStoreConsole();
            try {
                assertThat(console).isNotNull();
                assertThat(console.port()).as("the bound port, never the 0 asked for").isPositive();
                // Exactly the handle's own string: with an ephemeral port the log is the only place
                // that number exists, so a line reassembled at the log site could be wrong and pass.
                assertThat(log.infos)
                    .contains("graphitron:dev:   " + console.connectCommand());
                assertThat(DevMojo.coordinatesOf(console))
                    .isEqualTo(console.coordinates());
            } finally {
                if (console != null) {
                    console.close();
                }
            }
        }
    }

    @Test
    void storeConsole_pinnedPort_bindsExactlyThatPortAndTheCommandNamesIt() throws Exception {
        int pinned = PINNED_CONSOLE_PORT;
        try (var store = FactStores.inMemory()) {
            var mojo = new DevMojo();
            var log = new CapturingLog();
            mojo.setLog(log);
            mojo.environment = Map.of();
            var binding = new StoreConsoleBinding();
            binding.enabled = true;
            binding.port = pinned;
            mojo.storeConsole = binding;
            mojo.sessionStore = store;

            assertThat(mojo.resolveStoreConsole()).isEqualTo(pinned);
            var console = mojo.startStoreConsole();
            try {
                assertThat(console).isNotNull();
                assertThat(console.port()).isEqualTo(pinned);
                assertThat(log.infos).anySatisfy(line ->
                    assertThat(line).contains("-p " + pinned));
            } finally {
                if (console != null) {
                    console.close();
                }
            }
        }
    }

    @Test
    void storeConsole_envOverridesThePomOnBothFields() throws Exception {
        var mojo = new DevMojo();
        var binding = new StoreConsoleBinding();
        binding.enabled = true;
        binding.port = 54321;
        mojo.storeConsole = binding;
        mojo.environment = Map.of("GRAPHITRON_DEV_STORE_CONSOLE_PORT", "12345");
        assertThat(mojo.resolveStoreConsole()).isEqualTo(12345);

        mojo.environment = Map.of("GRAPHITRON_DEV_STORE_CONSOLE", "false");
        assertThat(mojo.resolveStoreConsole())
            .as("the env wins per field, including the field that turns it off")
            .isNull();
    }

    @Test
    void storeConsole_unparseablePort_failsLoudRatherThanFallingBackToEphemeral() {
        // A value the developer typed and this goal quietly ignored is worse than a stop: they would
        // read the ephemeral port off the log and conclude the pin works.
        var mojo = new DevMojo();
        mojo.environment = Map.of(
            "GRAPHITRON_DEV_STORE_CONSOLE", "true",
            "GRAPHITRON_DEV_STORE_CONSOLE_PORT", "eight-thousand");
        assertThatThrownBy(mojo::resolveStoreConsole)
            .isInstanceOf(MojoExecutionException.class)
            .hasMessageContaining("eight-thousand")
            .hasMessageContaining("ephemeral");
    }

    /**
     * A console that will not open costs the developer a debug tool and never the session, which is
     * the store's own posture that trouble in a convenience is warned about and continued past.
     * Driven by pointing the goal at a port something else holds.
     */
    @Test
    void storeConsole_thatCannotOpen_warnsWithTheReasonAndTheSessionContinues() throws Exception {
        try (var store = FactStores.inMemory();
             var taken = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            var mojo = new DevMojo();
            var log = new CapturingLog();
            mojo.setLog(log);
            mojo.environment = Map.of(
                "GRAPHITRON_DEV_STORE_CONSOLE", "true",
                "GRAPHITRON_DEV_STORE_CONSOLE_PORT", Integer.toString(taken.getLocalPort()));
            mojo.sessionStore = store;

            assertThat(mojo.startStoreConsole())
                .as("no console, and no exception out of the goal")
                .isNull();
            assertThat(log.warnings)
                .anySatisfy(line -> assertThat(line).contains("no fact-store console"))
                .anySatisfy(line -> assertThat(line).contains("h2.bindAddress"));
        }
    }

    /**
     * The console is a listener and a live handle, so the session has to give both back. Asserted as
     * "the port is free and the store is closed afterwards", which is what a leak actually looks
     * like: a console never closed keeps a port bound for the rest of the JVM.
     *
     * <p>That the console goes <em>before</em> the store, its link connection pointing there, is
     * stated at the teardown site rather than pinned here: with the store closed first the console's
     * own shutdown still succeeds, so the ordering leaves no observable trace to assert on.
     *
     * <p>Release is read off H2's own server state rather than by probing the port for silence: a
     * port nobody holds belongs to nobody, so in a parallel build something else listening there a
     * moment later would read as a leak.
     */
    @Test
    void cleanup_releasesTheConsoleAlongWithTheStore() throws Exception {
        var store = FactStores.inMemory();
        var mojo = new DevMojo();
        mojo.setLog(new CapturingLog());
        mojo.environment = Map.of("GRAPHITRON_DEV_STORE_CONSOLE", "true");
        mojo.sessionStore = store;
        mojo.storeConsoleHandle = mojo.startStoreConsole();
        assertThat(mojo.storeConsoleHandle).isNotNull();
        assertThat(mojo.storeConsoleHandle.running()).isTrue();

        mojo.cleanup();

        assertThat(mojo.storeConsoleHandle.running())
            .as("the console's listener is gone once the session tears down")
            .isFalse();
        assertThat(store.connection().isClosed())
            .as("and so is the store the console read through")
            .isTrue();
    }

    private static DevDatabaseBinding binding(String url, String user, String password,
            String dialect, String claims, Boolean allowClaimsOverride) {
        var binding = new DevDatabaseBinding();
        binding.url = url;
        binding.user = user;
        binding.password = password;
        binding.dialect = dialect;
        binding.claims = claims;
        binding.allowClaimsOverride = allowClaimsOverride;
        return binding;
    }

    /**
     * A context for a round that has to classify: unlike {@link #contextFor} it names the generated
     * jOOQ model the graphitron test-jar carries, so {@code @table} resolves and the round reaches
     * the validator rather than dying at the catalog.
     */
    private static RewriteContext jooqContextFor(Path basedir, Path schemaFile) {
        return new RewriteContext(
            List.of(SchemaInput.file(schemaFile)),
            basedir, "DevMojoTest",
            basedir.resolve("target/generated"),
            "com.example.generated",
            "no.sikt.graphitron.rewrite.test.jooq");
    }

    private static RewriteContext contextFor(Path basedir, Path schemaFile) {
        // Both failure modes occur during schema load, before any jOOQ catalog work,
        // so the jooq package / output directory values are never exercised.
        return new RewriteContext(
            List.of(SchemaInput.file(schemaFile)),
            basedir, "DevMojoTest",
            basedir.resolve("target/generated"),
            "com.example.generated",
            "com.example.jooq");
    }

    /** Maven {@link org.apache.maven.plugin.logging.Log} that records error calls instead of printing. */
    private static final class CapturingLog extends SystemStreamLog {
        final List<String> errors = new ArrayList<>();
        final List<Throwable> errorThrowables = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final List<String> infos = new ArrayList<>();

        @Override
        public void warn(CharSequence content) {
            warnings.add(String.valueOf(content));
        }

        @Override
        public void info(CharSequence content) {
            infos.add(String.valueOf(content));
        }

        @Override
        public void error(CharSequence content) {
            errors.add(String.valueOf(content));
        }

        @Override
        public void error(CharSequence content, Throwable error) {
            errors.add(String.valueOf(content));
            errorThrowables.add(error);
        }
    }

    private static DevMojo mojoFor(Path basedir, int port, int mcpPort) throws Exception {
        // Need a schema input on disk so buildContext() doesn't trip
        // before the bind path runs.
        Files.createDirectories(basedir.resolve("schema"));
        Files.writeString(basedir.resolve("schema/example.graphqls"), "type Query { x: Int }\n");

        var mojo = new DevMojo();
        var project = new MavenProject();
        project.setFile(basedir.resolve("pom.xml").toFile());
        mojo.project = project;
        mojo.outputPackage = "com.example.generated";
        mojo.jooqPackage = "com.example.jooq";
        mojo.outputDirectory = basedir.resolve("target/generated-sources/graphitron").toString();
        var binding = new SchemaInputBinding();
        binding.pattern = "schema/example.graphqls";
        mojo.schemaInputs = List.of(binding);
        mojo.port = port;
        mojo.mcpPort = mcpPort;
        mojo.debounceMs = 100;
        mojo.skipInitial = true; // we don't have a real catalog for buildContext to chew on
        // Never load the real BgeEmbedder ONNX model in the fast suite (it SIGSEGVs the surefire
        // fork). Default to structured-only warms; a test that needs to observe the warm unwind
        // overrides these with fakes it can assert against.
        mojo.embedderWarmFactory = () -> null;
        mojo.docsWarmFactory = () -> null;
        return mojo;
    }
}
