package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots a real {@link GraphitronMcpServer} on an ephemeral loopback port and drives it with the
 * MCP SDK's own Streamable HTTP client, asserting the contract end to end: the {@code initialize}
 * handshake carries the bundled instructions, the {@code about} prompt is advertised
 * argument-less and returns the bundled explainer, a taken port fails with an {@link IOException},
 * and (R361) the {@code tools} capability advertises the one liveness {@code status} tool whose
 * {@code tools/call} reflects the live {@link Workspace} snapshot state on both the default
 * {@code Unavailable} arm and a driven {@code Built.Current} arm. Infrastructure-tier; mirrors
 * {@code DevServerTest}. The ephemeral port (never the hard-coded {@code 8488}) keeps parallel CI
 * runs from colliding.
 */
class GraphitronMcpServerTest {

    @Test
    void initializeReturnsBundledInstructions() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {

            McpSchema.InitializeResult init = client.initialize();

            assertThat(init.instructions())
                .as("initialize handshake carries the bundled ambient instructions")
                .isNotNull();
            assertThat(init.instructions().strip())
                .isEqualTo(resource("/mcp/instructions.txt").strip());
            assertThat(init.serverInfo().name()).isEqualTo("graphitron");
        }
    }

    @Test
    void aboutPromptIsAdvertisedArgumentlessAndReturnsExplainer() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            var prompts = client.listPrompts().prompts();
            assertThat(prompts).hasSize(1);
            var about = prompts.getFirst();
            assertThat(about.name()).isEqualTo("about");
            assertThat(about.arguments() == null || about.arguments().isEmpty())
                .as("the about prompt takes no arguments")
                .isTrue();

            var result = client.getPrompt(McpSchema.GetPromptRequest.builder("about").build());
            assertThat(result.messages()).hasSize(1);
            var content = result.messages().getFirst().content();
            assertThat(content).isInstanceOf(McpSchema.TextContent.class);
            assertThat(((McpSchema.TextContent) content).text())
                .isEqualTo(resource("/mcp/about.md"));
        }
    }

    @Test
    void bindingTakenPortFailsWithIoException() throws Exception {
        try (var first = new GraphitronMcpServer(loopback(0), new Workspace())) {
            int port = first.port();
            assertThatThrownBy(() -> new GraphitronMcpServer(loopback(port), new Workspace()))
                .isInstanceOf(IOException.class);
        }
    }

    @Test
    void statusToolIsAdvertisedAndReportsUnavailableByDefault() throws Exception {
        // A fresh workspace has produced no build, so the snapshot defaults to Unavailable:
        // the freshness axis is absent, not null-valued.
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            var tools = client.listTools().tools();
            assertThat(tools).hasSize(1);
            var status = tools.getFirst();
            assertThat(status.name()).isEqualTo("status");

            var result = client.callTool(McpSchema.CallToolRequest.builder("status").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).hasSize(1);
            assertThat(result.content().getFirst()).isInstanceOf(McpSchema.TextContent.class);

            assertThat(result.structuredContent()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured)
                .containsEntry("toolsReady", true)
                .containsEntry("availability", "Unavailable")
                .doesNotContainKey("freshness");
        }
    }

    @Test
    void statusToolReflectsLiveBuiltCurrentSnapshot() throws Exception {
        // Drive a successful build into the live workspace before the call: the same handle the
        // server holds, so the tool reads Built/Current off it without any re-push.
        var workspace = new Workspace();
        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(CompletionData.empty(), snapshot),
            ValidationReport.empty());

        try (var server = new GraphitronMcpServer(loopback(0), workspace);
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("status").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            @SuppressWarnings("unchecked")
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured)
                .containsEntry("toolsReady", true)
                .containsEntry("availability", "Built")
                .containsEntry("freshness", "Current");
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

    private static String resource(String path) throws IOException {
        try (InputStream in = GraphitronMcpServerTest.class.getResourceAsStream(path)) {
            assertThat(in).as("test classpath resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
