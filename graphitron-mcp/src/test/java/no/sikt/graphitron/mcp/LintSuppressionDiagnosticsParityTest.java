package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R408 single-evaluator parity (MCP tier): a lint finding suppressed at the build does not surface
 * through the {@code diagnostics} tool. This drives the real
 * {@link GraphQLRewriteGenerator#buildOutput()} with a rule disabled, publishes the resulting
 * {@link no.sikt.graphitron.rewrite.ValidationReport} onto a {@link Workspace} exactly as the dev loop
 * does, and asserts the live MCP server omits the disabled rule from the {@code diagnostics}
 * projection while still reporting a co-present, non-disabled rule. Because suppression rides that one
 * report and not a Maven-log-only filter, the agent-facing tool is suppressed for free.
 */
class LintSuppressionDiagnosticsParityTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private static final String SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
        }
        type Query { film: Film }
        """;

    @Test
    @SuppressWarnings("unchecked")
    void buildSuppressedFindingDoesNotSurfaceThroughDiagnostics(@TempDir Path tmp) throws Exception {
        var workspace = workspaceWithSuppression(tmp,
            LintConfig.validated(Set.of("field-names-camel-case"), List.of()));

        try (var server = new GraphitronMcpServer(loopback(0), workspace);
             var client = connect(server.port())) {
            client.initialize();
            var result = client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build());
            var structured = (Map<String, Object>) result.structuredContent();
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");

            assertThat(diagnostics)
                .as("the build-suppressed rule does not surface through the diagnostics tool")
                .noneMatch(d -> "field-names-camel-case".equals(d.get("lintRule")));
            assertThat(diagnostics)
                .as("a non-disabled rule still surfaces, proving selective build-side suppression")
                .anyMatch(d -> "types-and-fields-have-descriptions".equals(d.get("lintRule")));
        }
    }

    private static Workspace workspaceWithSuppression(Path tmp, LintConfig lintConfig) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, SDL);
        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            tmp, tmp, "fake.output", JOOQ_PACKAGE, Map.of()
        ).withLintConfig(lintConfig);

        var output = new GraphQLRewriteGenerator(ctx).buildOutput();
        var workspace = new Workspace();
        workspace.setBuildOutput(output.artifacts(), output.report());
        return workspace;
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }

    private static McpSyncClient connect(int port) {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port)
            .endpoint(GraphitronMcpServer.MCP_ENDPOINT)
            .build();
        return McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(10))
            .initializationTimeout(Duration.ofSeconds(10))
            .build();
    }
}
