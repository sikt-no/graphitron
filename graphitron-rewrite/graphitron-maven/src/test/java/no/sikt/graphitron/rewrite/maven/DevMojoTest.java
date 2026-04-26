package no.sikt.graphitron.rewrite.maven;

import no.sikt.graphitron.rewrite.maven.dev.DevServer;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
        // plan-graphitron-lsp.md (port 8487, loopback bind).
        assertThat(DevMojo.DEFAULT_PORT).isEqualTo(8487);
        assertThat(DevMojo.LOOPBACK_HOST).isEqualTo("127.0.0.1");
    }

    @Test
    void bindToTakenPortFailsWithOverrideHint(@TempDir Path basedir) throws Exception {
        // Occupy a port so the Mojo's bind path hits BindException.
        try (var blocker = new DevServer(
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            new no.sikt.graphitron.lsp.state.Workspace())) {
            int taken = blocker.port();

            var mojo = mojoFor(basedir, taken);

            assertThatThrownBy(mojo::execute)
                .isInstanceOf(MojoExecutionException.class)
                .hasMessageContaining(String.valueOf(taken))
                .hasMessageContaining("-Dgraphitron.dev.port=N");
        }
    }

    private static DevMojo mojoFor(Path basedir, int port) throws Exception {
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
        mojo.debounceMs = 100;
        mojo.skipInitial = true; // we don't have a real catalog for buildContext to chew on
        return mojo;
    }
}
