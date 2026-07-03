package no.sikt.graphitron.rewrite.maven;

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
        // plan-graphitron-lsp.md (port 8487, loopback bind) and R341 (MCP port 8488).
        // This pins the Java source of truth for the MCP port so it cannot silently drift
        // from the design; the static copies (.mcp.json, docs) are accepted drift per R341.
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
        // bind that R341 promotes into scope: the user-visible MojoExecutionException, not the
        // server-level IOException GraphitronMcpServerTest covers.
        // The Workspace handed in is orthogonal to this test: it exercises only the bind-failure
        // unwind, which is driven by the address, so an empty workspace suffices.
        try (var mcpBlocker = new GraphitronMcpServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), new Workspace())) {
            int takenMcpPort = mcpBlocker.port();

            var mojo = mojoFor(basedir, 0, takenMcpPort);
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

        boolean ok = mojo.runGeneratorPass(contextFor(basedir, broken), "regen");

        assertThat(ok).isFalse();
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

        boolean ok = mojo.runGeneratorPass(contextFor(basedir, missing), "regen");

        assertThat(ok).isFalse();
        assertThat(log.errorThrowables)
            .as("genuine infrastructure failure keeps its diagnostic stack trace")
            .isNotEmpty();
        assertThat(log.errors)
            .anySatisfy(line -> assertThat(line).contains("failed (infrastructure)"));
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

        mojo.maybeStartIncrementalCompiler(new Workspace());

        assertThat(mojo.incrementalCompiler)
            .as("compile opt-out: no incremental compile driver is built")
            .isNull();
        assertThat(Files.exists(basedir.resolve("target/graphitron-classes")))
            .as("compile opt-out: the exclusive output dir is never created")
            .isFalse();
    }

    @Test
    void reportCompile_failure_rendersConsoleBlockAndPublishesToMcpChannel() {
        // A failed compile round surfaces through the two channels the spec names: the console (a
        // labelled generated-code block) and the MCP diagnostics channel (Workspace.compileDiagnostics,
        // which the diagnostics tool tags source:"compile"). One assertion per channel.
        var workspace = new Workspace();
        var diagnostic = new CompileDiagnostic(
            "gen/pkg/FilmFetchers.java", 12, 7, "ERROR", "cannot find symbol");
        var outcome = new CompileOutcome(
            new CompileRound(false, List.of(diagnostic)), Set.of("gen.pkg.FilmFetchers"));
        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        mojo.reportCompile(workspace, outcome, "recompile");

        assertThat(log.errors)
            .as("console: a labelled generated-code compile block naming the offending file")
            .anySatisfy(line -> assertThat(line)
                .contains("generated-code compilation failed")
                .contains("gen/pkg/FilmFetchers.java")
                .contains("cannot find symbol"));
        assertThat(workspace.compileDiagnostics())
            .as("MCP channel: the round's diagnostics are published for the diagnostics tool")
            .containsExactly(diagnostic);
    }

    @Test
    void reportCompile_success_clearsAPriorFailureAndLogsNoError() {
        // A clean round publishes the empty list, clearing a prior failure so the diagnostics tool no
        // longer shows a stale compile error, and it does not log an error.
        var workspace = new Workspace();
        workspace.setCompileDiagnostics(List.of(
            new CompileDiagnostic("gen/pkg/Old.java", 1, 1, "ERROR", "stale")));
        var outcome = new CompileOutcome(
            new CompileRound(true, List.of()), Set.of("gen.pkg.A", "gen.pkg.B"));
        var log = new CapturingLog();
        var mojo = new DevMojo();
        mojo.setLog(log);

        mojo.reportCompile(workspace, outcome, "recompile");

        assertThat(workspace.compileDiagnostics())
            .as("a clean round clears the prior failure")
            .isEmpty();
        assertThat(log.errors)
            .as("a clean round logs no error")
            .isEmpty();
    }

    private static RewriteContext contextFor(Path basedir, Path schemaFile) {
        // Both failure modes occur during schema load, before any jOOQ catalog work,
        // so the jooq package / output directory values are never exercised.
        return new RewriteContext(
            List.of(SchemaInput.plain(schemaFile.toString())),
            basedir,
            basedir.resolve("target/generated"),
            "com.example.generated",
            "com.example.jooq",
            Map.of());
    }

    /** Maven {@link org.apache.maven.plugin.logging.Log} that records error calls instead of printing. */
    private static final class CapturingLog extends SystemStreamLog {
        final List<String> errors = new ArrayList<>();
        final List<Throwable> errorThrowables = new ArrayList<>();

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
