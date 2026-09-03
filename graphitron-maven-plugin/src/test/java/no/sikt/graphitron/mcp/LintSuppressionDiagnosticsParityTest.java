package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.lint.LintConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single-evaluator parity (MCP tier): a lint finding suppressed at the build does not surface
 * through the {@code diagnostics} tool. This drives the real
 * {@link GraphQLRewriteGenerator#buildOutput()} with a rule disabled through the store-backed
 * fixture ({@link StoreBackedBuild}): the warning loader takes the suppression-filtered list the
 * report was assembled from, so the {@code lint_finding} rows the tool projects are
 * post-suppression survivors, and the live MCP server omits the disabled rule while still
 * reporting a co-present, non-disabled rule. Because suppression is applied before the one list
 * both the report and the loader read, the agent-facing tool is suppressed for free, with no
 * second filter to drift.
 */
class LintSuppressionDiagnosticsParityTest {

    private static final String SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
        }
        type Query { film: Film }
        """;

    @Test
    @SuppressWarnings("unchecked")
    void buildSuppressedFindingDoesNotSurfaceThroughDiagnostics(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "LintSuppressionDiagnosticsParityTest", SDL,
                LintConfig.validated(Set.of("field-names-camel-case"), List.of()));
             var server = new GraphitronMcpServer(loopback(0),
                 null, null, null, null, build.handle(), build.reader());
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
