package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            assertThat(tools).extracting(McpSchema.Tool::name)
                .containsExactlyInAnyOrder("status", "catalog.tables", "catalog.describe");

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

    // ---- R362: catalog.tables / catalog.describe ----

    @Test
    void catalogTablesListsAllTablesWithSchemaAndComment() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("catalog.tables").build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);

            var structured = structured(result);
            @SuppressWarnings("unchecked")
            var tables = (List<Map<String, Object>>) structured.get("tables");
            assertThat(tables).hasSize(3);
            assertThat(tables).extracting(t -> t.get("name"))
                .containsExactly("film", "actor", "film");
            // public.film carries a comment; public.actor does not (comment omitted, not null-valued).
            assertThat(tables.get(0)).containsEntry("schema", "public").containsEntry("comment", "Films catalog");
            assertThat(tables.get(1)).containsEntry("schema", "public").doesNotContainKey("comment");
            assertThat(structured).doesNotContainKey("nextCursor");
        }
    }

    @Test
    void catalogTablesFiltersBySchemaAndNameSubstring() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var bySchema = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.tables")
                .arguments(Map.of("schema", "other")).build()));
            @SuppressWarnings("unchecked")
            var otherTables = (List<Map<String, Object>>) bySchema.get("tables");
            assertThat(otherTables).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("schema", "other").containsEntry("name", "film"));

            var byName = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.tables")
                .arguments(Map.of("name", "act")).build()));
            @SuppressWarnings("unchecked")
            var actTables = (List<Map<String, Object>>) byName.get("tables");
            assertThat(actTables).singleElement()
                .satisfies(t -> assertThat(t).containsEntry("name", "actor"));
        }
    }

    @Test
    void catalogTablesPagesWithCursor() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            // limit=2 over 3 tables yields a first page of 2 plus a nextCursor.
            var page1 = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.tables")
                .arguments(Map.of("limit", 2)).build()));
            @SuppressWarnings("unchecked")
            var first = (List<Map<String, Object>>) page1.get("tables");
            assertThat(first).hasSize(2);
            assertThat(page1).containsKey("nextCursor");

            // Following the cursor reaches the tail (1 table) with no further nextCursor.
            var page2 = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.tables")
                .arguments(Map.of("limit", 2, "cursor", page1.get("nextCursor"))).build()));
            @SuppressWarnings("unchecked")
            var second = (List<Map<String, Object>>) page2.get("tables");
            assertThat(second).hasSize(1);
            assertThat(page2).doesNotContainKey("nextCursor");
        }
    }

    @Test
    void catalogDescribeReturnsStructuredShapeForResolvedTable() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.describe")
                .arguments(Map.of("table", "public.film")).build()));
            assertThat(structured).containsEntry("resolution", "resolved")
                .containsEntry("schema", "public").containsEntry("name", "film")
                .containsEntry("comment", "Films catalog");

            @SuppressWarnings("unchecked")
            var columns = (List<Map<String, Object>>) structured.get("columns");
            assertThat(columns).extracting(c -> c.get("sqlName")).containsExactly("film_id", "title");
            assertThat(columns.get(0))
                .containsEntry("javaName", "FILM_ID").containsEntry("sqlType", "integer")
                .containsEntry("nullable", false).doesNotContainKey("comment");
            assertThat(columns.get(1)).containsEntry("comment", "Display title");

            @SuppressWarnings("unchecked")
            var primaryKey = (Map<String, Object>) structured.get("primaryKey");
            assertThat(primaryKey).containsEntry("constraintName", "film_pkey")
                .containsEntry("columns", List.of("film_id"));

            @SuppressWarnings("unchecked")
            var foreignKeys = (Map<String, Object>) structured.get("foreignKeys");
            @SuppressWarnings("unchecked")
            var outgoing = (List<Map<String, Object>>) foreignKeys.get("outgoing");
            assertThat(outgoing).singleElement().satisfies(fk -> assertThat(fk)
                .containsEntry("targetTable", "public.language")
                .containsEntry("columns", List.of("language_id"))
                .containsEntry("targetColumns", List.of("language_id")));
            @SuppressWarnings("unchecked")
            var incoming = (List<Map<String, Object>>) foreignKeys.get("incoming");
            assertThat(incoming).singleElement().satisfies(fk -> assertThat(fk)
                .containsEntry("sourceTable", "public.film_actor"));
        }
    }

    @Test
    void catalogDescribeReturnsAmbiguousForNameCarriedByTwoSchemas() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.describe")
                .arguments(Map.of("table", "film")).build()));
            assertThat(structured).containsEntry("resolution", "ambiguous");
            @SuppressWarnings("unchecked")
            var schemas = (List<String>) structured.get("schemas");
            assertThat(schemas).containsExactlyInAnyOrder("public", "other");
        }
    }

    @Test
    void catalogDescribeReturnsNotFoundForUnknownName() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.describe")
                .arguments(Map.of("table", "nope")).build()));
            assertThat(structured).containsEntry("resolution", "notFound").containsEntry("table", "nope");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    private static Workspace workspaceWith(CatalogFacts facts) {
        var workspace = new Workspace();
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(
                CompletionData.empty(),
                new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of()),
                facts),
            ValidationReport.empty());
        return workspace;
    }

    /**
     * A hand-built two-schema catalog projection: {@code public.film} (commented, with a column
     * comment, a PK, and an outgoing + incoming FK), {@code public.actor} (no comment), and
     * {@code other.film} (so the bare name {@code film} is ambiguous). Insertion order is the page
     * order the tools assert against.
     */
    private static CatalogFacts catalogFixture() {
        var publicFilm = new CatalogFacts.Table(
            "public", "film", Optional.of("Films catalog"),
            List.of(
                new CatalogFacts.Column("film_id", "FILM_ID", "integer", false, Optional.empty()),
                new CatalogFacts.Column("title", "TITLE", "varchar", false, Optional.of("Display title"))),
            Optional.of(new CatalogFacts.Key("film_pkey", List.of("film_id"))),
            List.of(),
            List.of(new CatalogFacts.Index("idx_title", List.of("title"))),
            new CatalogFacts.ForeignKeys(
                List.of(new CatalogFacts.OutgoingForeignKey(
                    "film_language_id_fkey", "public.language", List.of("language_id"), List.of("language_id"))),
                List.of(new CatalogFacts.IncomingForeignKey(
                    "film_actor_film_id_fkey", "public.film_actor", List.of("film_id"), List.of("film_id")))));
        var publicActor = new CatalogFacts.Table(
            "public", "actor", Optional.empty(),
            List.of(new CatalogFacts.Column("actor_id", "ACTOR_ID", "integer", false, Optional.empty())),
            Optional.of(new CatalogFacts.Key("actor_pkey", List.of("actor_id"))),
            List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var otherFilm = new CatalogFacts.Table(
            "other", "film", Optional.empty(),
            List.of(new CatalogFacts.Column("id", "ID", "integer", false, Optional.empty())),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var map = new LinkedHashMap<String, CatalogFacts.Table>();
        map.put("public.film", publicFilm);
        map.put("public.actor", publicActor);
        map.put("other.film", otherFilm);
        return new CatalogFacts(map);
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
