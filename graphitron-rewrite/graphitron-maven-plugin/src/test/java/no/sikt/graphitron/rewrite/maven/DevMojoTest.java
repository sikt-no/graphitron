package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.mcp.GraphitronMcpServer;
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
        try (var mcpBlocker = new GraphitronMcpServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))) {
            int takenMcpPort = mcpBlocker.port();

            var mojo = mojoFor(basedir, 0, takenMcpPort);

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
        return mojo;
    }
}
