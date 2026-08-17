package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.FakeEmbedder;
import no.sikt.graphitron.mcp.rag.RagConfig;
import no.sikt.graphitron.mcp.rag.docs.DocChunk;
import no.sikt.graphitron.mcp.rag.docs.DocsBundle;
import no.sikt.graphitron.mcp.rag.docs.DocsIndex;
import no.sikt.graphitron.mcp.rag.docs.DocsRag;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Boots a real {@link GraphitronMcpServer} on an ephemeral loopback port and drives it with the
 * MCP SDK's own Streamable HTTP client, asserting the contract end to end: the {@code initialize}
 * handshake carries the bundled instructions, the {@code about} prompt is advertised
 * argument-less and returns the bundled explainer, a taken port fails with an {@link IOException},
 * and the {@code tools} capability advertises the one liveness {@code status} tool whose
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
                .containsExactlyInAnyOrder("status", "catalog.tables", "catalog.describe",
                    "services", "conditions", "records", "schema", "diagnostics",
                    "diagnostics.aggregate", "edges", "docs.search", "catalog.search");

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
    void executeToolIsAdvertisedExactlyWhenADevDatabaseIsConfigured() throws Exception {
        // The degrade-gracefully posture, stronger than the RAG tools' advertised-but-degrading:
        // with no dev database the execute tool is simply absent (pinned by the containsExactlyInAnyOrder
        // in statusToolIsAdvertisedAndReportsUnavailableByDefault); with one configured it appears.
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), null, null, null, executeConfig());
             var client = connect(server.port())) {
            client.initialize();

            var tools = client.listTools().tools();
            assertThat(tools).extracting(McpSchema.Tool::name).contains("execute");
        }
    }

    @Test
    void instructionsCarryTheExecuteTailExactlyWhenTheToolIsRegistered() throws Exception {
        // The ambient instructions are composed rather than read from one fixed resource, so the
        // execute routing sentence tracks that tool's conditional registration. The sibling
        // initializeReturnsBundledInstructions boots without a dev database and so pins the base arm
        // verbatim; this is the composed arm. ServerInstructionsTest pins what the prose has to cover.
        String tail = resource("/mcp/instructions-execute.txt").strip();

        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            assertThat(client.initialize().instructions())
                .as("with no dev database the execute tail must not appear")
                .doesNotContain(tail);
        }
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), null, null, null, executeConfig());
             var client = connect(server.port())) {
            assertThat(client.initialize().instructions().strip())
                .as("with a dev database configured the base block carries the execute tail")
                .startsWith(resource("/mcp/instructions.txt").strip())
                .endsWith(tail);
        }
    }

    private static ExecuteTool.Config executeConfig() {
        return new ExecuteTool.Config(
            new DevQueryExecutor.Wiring("com.example", java.nio.file.Path.of("target/graphitron-classes"),
                List.of()),
            new DevQueryExecutor.DbConfig("jdbc:postgresql://localhost/dev", "dev", "dev", "POSTGRES", null),
            false);
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

    // ---- catalog.tables / catalog.describe ----

    /**
     * The schema the catalog cases capture under. Minimal on purpose: the catalog census is a walk of
     * the generated jOOQ model rather than of the SDL, so what the schema binds does not decide which
     * tables are captured, and a case asserting on the census wants the census rather than a schema.
     */
    private static final String CATALOG_SDL = """
        type Film @table(name: "film") {
          film_id: Int
        }
        type Query {
          film: Film
        }
        """;

    /**
     * The wire fields are the shipped ones; what changed is where they come from and the order they
     * arrive in. Table order is the census's {@code (schema, name)} ordering rather than the
     * generated {@code Tables} class's reflective field order, which the JDK does not promise is
     * stable at all.
     *
     * <p>Both arms of the {@code comment} slot come from the fixture's own DDL rather than from a
     * hand-built value: {@code film} declares a table comment and {@code actor} deliberately declares
     * none, so present and absent are each reachable from a real capture. This is the one assertion
     * in the module that carries the description column end to end, from the {@code COMMENT ON} the
     * fixture declares through the jOOQ crawler to the wire.
     */
    @Test
    void catalogTablesListsTheGraphsCensusOrderedBySchemaThenName(@TempDir Path tmp) {
        try (var build = StoreBackedBuild.run(tmp, "catalog-tables", CATALOG_SDL)) {
            var structured = structured(
                GraphitronMcpServer.catalogTablesResult(build.handle(), Map.of()));

            @SuppressWarnings("unchecked")
            var tables = (List<Map<String, Object>>) structured.get("tables");
            assertThat(tables).isNotEmpty();
            assertThat(tables).extracting(t -> t.get("schema")).containsOnly("public");

            var names = tables.stream().map(t -> (String) t.get("name")).toList();
            assertThat(names)
                .as("the census, in the ordering the page is keyed by")
                .contains("actor", "film", "project_note")
                .isSorted();
            assertThat(tables).filteredOn(t -> "film".equals(t.get("name")))
                .as("a table whose DDL declares a comment carries it")
                .singleElement()
                .satisfies(t -> assertThat(t)
                    .containsEntry("comment", "One film in the rental catalogue."));
            assertThat(tables).filteredOn(t -> "actor".equals(t.get("name")))
                .as("a table whose DDL declares none omits the slot rather than sending null")
                .singleElement()
                .satisfies(t -> assertThat(t).doesNotContainKey("comment"));
        }
    }

    @Test
    void catalogTablesFiltersBySchemaAndNameSubstring(@TempDir Path tmp) {
        try (var build = StoreBackedBuild.run(tmp, "catalog-filters", CATALOG_SDL)) {
            // The schema filter is exact and case-insensitive; every test table lives in public.
            var bySchema = structured(GraphitronMcpServer.catalogTablesResult(
                build.handle(), Map.of("schema", "PUBLIC")));
            @SuppressWarnings("unchecked")
            var inPublic = (List<Map<String, Object>>) bySchema.get("tables");
            assertThat(inPublic).isNotEmpty();
            assertThat(inPublic).extracting(t -> t.get("schema")).containsOnly("public");

            var noSuchSchema = structured(GraphitronMcpServer.catalogTablesResult(
                build.handle(), Map.of("schema", "other")));
            @SuppressWarnings("unchecked")
            var elsewhere = (List<Map<String, Object>>) noSuchSchema.get("tables");
            assertThat(elsewhere).isEmpty();

            // The name filter is a case-insensitive substring, so it reaches every table carrying it.
            var byName = structured(GraphitronMcpServer.catalogTablesResult(
                build.handle(), Map.of("name", "ACT")));
            @SuppressWarnings("unchecked")
            var actTables = (List<Map<String, Object>>) byName.get("tables");
            assertThat(actTables.stream().map(t -> (String) t.get("name")))
                .contains("actor", "film_actor")
                .allSatisfy(n -> assertThat(n).contains("act"));
        }
    }

    /**
     * Paging is keyset on the ordering pair, so following the cursor to exhaustion visits the
     * unpaged census exactly once. That is the property an offset cursor could not promise: an offset
     * is only meaningful against an order that is stable between calls, and under keyset the ordering
     * <em>is</em> the cursor.
     *
     * <p>The summary's total stays the whole filtered census on every page rather than the remainder,
     * which is what tells an agent whether paging is worth starting.
     */
    @Test
    void catalogTablesPagesByKeysetAndVisitsEveryTableOnce(@TempDir Path tmp) {
        try (var build = StoreBackedBuild.run(tmp, "catalog-paging", CATALOG_SDL)) {
            var unpaged = structured(GraphitronMcpServer.catalogTablesResult(build.handle(), Map.of()));
            @SuppressWarnings("unchecked")
            var all = (List<Map<String, Object>>) unpaged.get("tables");
            var expected = all.stream().map(t -> (String) t.get("name")).toList();
            assertThat(unpaged).doesNotContainKey("nextCursor");

            var walked = new java.util.ArrayList<String>();
            Optional<String> cursor = Optional.empty();
            int pages = 0;
            do {
                var args = new LinkedHashMap<String, Object>();
                args.put("limit", 2);
                cursor.ifPresent(c -> args.put("cursor", c));
                var result = GraphitronMcpServer.catalogTablesResult(build.handle(), args);
                var page = structured(result);

                assertThat(firstLine(result))
                    .as("the total is the whole filtered census on every page, not the remainder")
                    .startsWith("catalog.tables: " + expected.size() + " table(s)");

                @SuppressWarnings("unchecked")
                var entries = (List<Map<String, Object>>) page.get("tables");
                assertThat(entries).hasSizeBetween(1, 2);
                entries.forEach(e -> walked.add((String) e.get("name")));
                cursor = Optional.ofNullable((String) page.get("nextCursor"));
            } while (cursor.isPresent() && ++pages < expected.size());

            assertThat(walked)
                .as("keyset paging visits the census once, in order, with nothing skipped or repeated")
                .containsExactlyElementsOf(expected);
        }
    }

    /**
     * A handle-less server refuses rather than answering an empty census, which would read as a
     * database with no tables. Distinct from a store holding no rows yet, which is an answer.
     */
    @Test
    void catalogTablesRefusesWithoutAStoreHandle() {
        var result = GraphitronMcpServer.catalogTablesResult(null, Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(firstLine(result))
            .startsWith("catalog.tables:")
            .contains("holds no fact store handle");
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

    // ---- services / conditions / records ----

    @Test
    @SuppressWarnings("unchecked")
    void servicesListsClassesWithMethodRefsAndResolvedClassLocation() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), codeWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("services").build()));
            var services = (List<Map<String, Object>>) structured.get("services");
            // The record-only FilmCard is not a service (no methods); only FilmService surfaces here.
            assertThat(services).singleElement().satisfies(s -> {
                assertThat(s).containsEntry("classRef", "com.example.FilmService")
                    .containsEntry("className", "com.example.FilmService");
                var methods = (List<Map<String, Object>>) s.get("methods");
                // Condition methods appear here too: services is the un-filtered class view.
                assertThat(methods).extracting(m -> m.get("methodRef"))
                    .containsExactly("com.example.FilmService#list/0", "com.example.FilmService#activeFilms/0");
                // Class location resolved off the source index.
                var location = (Map<String, Object>) s.get("location");
                assertThat(location).containsEntry("uri", "file:///src/FilmService.java").containsEntry("line", 4);
                assertThat(s).doesNotContainKey("locationStatus");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void conditionsListsOnlyConditionMethodsWithResolvedLocation() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), codeWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("conditions").build()));
            var conditions = (List<Map<String, Object>>) structured.get("conditions");
            assertThat(conditions).singleElement().satisfies(c -> {
                assertThat(c).containsEntry("methodRef", "com.example.FilmService#activeFilms/0")
                    .containsEntry("className", "com.example.FilmService")
                    .containsEntry("name", "activeFilms");
                var location = (Map<String, Object>) c.get("location");
                assertThat(location).containsEntry("uri", "file:///src/FilmService.java").containsEntry("line", 10);
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsListsComponentsAndYieldsNotIndexedLocationArm() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), codeWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("records").build()));
            var records = (List<Map<String, Object>>) structured.get("records");
            assertThat(records).singleElement().satisfies(r -> {
                assertThat(r).containsEntry("classRef", "com.example.FilmCard");
                var components = (List<Map<String, Object>>) r.get("components");
                assertThat(components).extracting(c -> c.get("name")).containsExactly("filmId", "title");
                // FilmCard is not in the source index: the degraded arm is location-absent, not an error.
                assertThat(r).doesNotContainKey("location").containsEntry("locationStatus", "notIndexed");
            });
        }
    }

    // ---- schema ----

    @Test
    @SuppressWarnings("unchecked")
    void schemaNarrowsToOneTypeWithClassificationBackingNodeAndFields() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), schemaWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("schema")
                .arguments(Map.of("type", "Film")).build()));
            assertThat(structured).containsEntry("availability", "Built").containsEntry("freshness", "Current");
            var types = (List<Map<String, Object>>) structured.get("types");
            assertThat(types).singleElement().satisfies(t -> {
                assertThat(t).containsEntry("typeRef", "Film");
                var classification = (Map<String, Object>) t.get("typeClassification");
                assertThat(classification).containsEntry("kind", "Node").containsEntry("tableName", "film");
                var backing = (Map<String, Object>) t.get("backingShape");
                assertThat(backing).containsEntry("kind", "TableBacking").containsEntry("tableName", "film");
                // @node arm joined off the catalog (the snapshot carries no @node projection).
                var node = (Map<String, Object>) t.get("node");
                assertThat(node).containsEntry("typeId", "FilmType").containsEntry("keyColumns", List.of("film_id"));
                var fields = (List<Map<String, Object>>) t.get("fields");
                assertThat(fields).singleElement().satisfies(f -> {
                    assertThat(f).containsEntry("fieldRef", "Film.title");
                    assertThat((Map<String, Object>) f.get("classification")).containsEntry("kind", "Column");
                });
                var loc = (Map<String, Object>) t.get("definitionLocation");
                assertThat(loc).containsEntry("uri", "file:///schema.graphqls");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaListsTypesPagedAndOmitsNodeForNonNodeType() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), schemaWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            // limit=1 over the two types (Actor, Film sorted) yields a first page plus a cursor.
            var page1 = structured(client.callTool(McpSchema.CallToolRequest.builder("schema")
                .arguments(Map.of("limit", 1)).build()));
            var first = (List<Map<String, Object>>) page1.get("types");
            assertThat(first).singleElement().satisfies(t -> {
                assertThat(t).containsEntry("typeRef", "Actor");
                // A plain @table type carries no @node block.
                assertThat(t).doesNotContainKey("node");
            });
            assertThat(page1).containsKey("nextCursor");

            var page2 = structured(client.callTool(McpSchema.CallToolRequest.builder("schema")
                .arguments(Map.of("limit", 1, "cursor", page1.get("nextCursor"))).build()));
            var second = (List<Map<String, Object>>) page2.get("types");
            assertThat(second).extracting(t -> t.get("typeRef")).containsExactly("Film");
            assertThat(page2).doesNotContainKey("nextCursor");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaRendersConflictedClaimsAndUnresolvableReasonOnTheWire() throws Exception {
        // The first-client contract for the failure arms: a conflicted field renders every rival
        // claim with its decoded slot facts under the store's classifier vocabulary (the same
        // dmlKind/tableName keys a healthy DmlMutation renders), and an unresolvable field
        // renders its rejection reason. Pins the JSON keys, not just the catalog records.
        try (var server = new GraphitronMcpServer(loopback(0), conflictedWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("schema")
                .arguments(Map.of("type", "Mutation")).build()));
            var types = (List<Map<String, Object>>) structured.get("types");
            assertThat(types).singleElement().satisfies(t -> {
                var fields = (List<Map<String, Object>>) t.get("fields");
                assertThat(fields).extracting(f -> f.get("fieldRef"))
                    .containsExactly("Mutation.broken", "Mutation.deleteFilm");

                var broken = (Map<String, Object>) fields.get(0).get("classification");
                assertThat(broken).containsEntry("kind", "Unresolvable")
                    .containsEntry("reason", "no matching classification rule");

                var conflicted = (Map<String, Object>) fields.get(1).get("classification");
                assertThat(conflicted).containsEntry("kind", "Conflicted")
                    .containsEntry("violation", "@service, @mutation are mutually exclusive");
                var claims = (List<Map<String, Object>>) conflicted.get("claims");
                assertThat(claims).hasSize(2);
                assertThat(claims.get(0))
                    .containsEntry("classifier", "Service")
                    .containsEntry("methodClassName", "com.example.FilmService")
                    .containsEntry("methodName", "delete")
                    .containsEntry("trigger", "@service")
                    .containsEntry("decoded", true);
                // An unpositioned claim omits the location field rather than emitting null.
                assertThat(claims.get(0)).doesNotContainKey("location");
                assertThat(claims.get(1))
                    .containsEntry("classifier", "Mutation")
                    .containsEntry("dmlKind", "DELETE")
                    .containsEntry("tableName", "film")
                    .containsEntry("trigger", "@mutation")
                    .containsEntry("decoded", true);
                var location = (Map<String, Object>) claims.get(1).get("location");
                assertThat(location).containsEntry("uri", "file:///schema.graphqls")
                    .containsEntry("line", 11);
            });

            // A chained @routine crosses the wire as ONE claim whose routineRefs carry the
            // steps in application-ordinal order; the chain never renders as rival claims.
            var query = structured(client.callTool(McpSchema.CallToolRequest.builder("schema")
                .arguments(Map.of("type", "Query")).build()));
            var queryTypes = (List<Map<String, Object>>) query.get("types");
            assertThat(queryTypes).singleElement().satisfies(t -> {
                var fields = (List<Map<String, Object>>) t.get("fields");
                var films = (Map<String, Object>) fields.getFirst().get("classification");
                var claims = (List<Map<String, Object>>) films.get("claims");
                assertThat(claims).hasSize(2);
                assertThat(claims.get(1))
                    .containsEntry("classifier", "Routine")
                    .containsEntry("routineRefs", List.of("first_fn", "second_fn"));
            });
        }
    }

    @Test
    void schemaReportsUnavailableBeforeFirstBuild() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("schema").build()));
            assertThat(structured).containsEntry("availability", "Unavailable").containsEntry("types", List.of());
        }
    }

    // ---- diagnostics / diagnostics.aggregate (store-backed) ----

    /**
     * The store-backed diagnostics fixture: an unresolved column (the error), snake_case names
     * (lint findings), and {@code @table} on an input type (the rule-less advisory through a
     * real producer). The loaders read the walk's own pre-fuse streams, so the rows these tests
     * assert on come from a real pipeline run rather than a hand-built report.
     */
    private static final String DIAGNOSTICS_SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
          badColumn: Int
        }
        input FilmInput @table(name: "film") {
          film_id: Int
        }
        type Query {
          film(where: FilmInput): Film
        }
        """;

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsReturnsMappedErrorsAndReportsSnapshotFreshness(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-diagnostics", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");

            var error = diagnostics.stream()
                .filter(d -> "error".equals(d.get("severity"))).findFirst().orElseThrow();
            assertThat(error)
                .containsEntry("source", "schema")
                .containsEntry("coordinate", "Film.badColumn")
                .containsEntry("rejectionKind", "author-error");
            assertThat((String) error.get("message")).contains("badColumn");
            var location = (Map<String, Object>) error.get("location");
            // The canonical file URI, and the 1-based stored position on the 0-based wire.
            assertThat((String) location.get("uri")).startsWith("file:").endsWith("schema.graphqls");
            assertThat(location).containsEntry("line", 2);

            // The rule-less advisory (a real producer: @table on an input type) keeps surfacing
            // as a warning with no lintRule key.
            assertThat(diagnostics).anySatisfy(d -> {
                assertThat(d).containsEntry("severity", "warning").doesNotContainKey("lintRule");
                assertThat((String) d.get("message")).contains("@table");
            });
            // Snapshot axes reported alongside so an agent can tell whether diagnostics are current.
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsProjectsLintRuleIdForLintFindings(@TempDir Path tmp) throws Exception {
        // A lint finding rides the suppression-filtered warning list into the lint_finding arm,
        // and the diagnostics tool projects its typed LintRule id onto the wire, so an MCP-aware
        // agent sees which rule fired.
        try (var build = StoreBackedBuild.run(tmp, "mcp-lint", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).anySatisfy(d -> assertThat(d)
                .containsEntry("severity", "warning")
                .containsEntry("lintRule", "field-names-camel-case"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsFiltersBySeverity(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-severity", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics")
                .arguments(Map.of("severity", "error")).build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).isNotEmpty();
            assertThat(diagnostics).allSatisfy(d -> assertThat(d).containsEntry("severity", "error"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsAggregateAnswersTheTriagePresetEndToEnd(@TempDir Path tmp) throws Exception {
        try (var build = StoreBackedBuild.run(tmp, "mcp-aggregate", DIAGNOSTICS_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(
                McpSchema.CallToolRequest.builder("diagnostics.aggregate").build()));
            assertThat(structured.get("groupBy")).isEqualTo(List.of("actionable", "kind"));
            var groups = (List<Map<String, Object>>) structured.get("groups");
            assertThat(groups).isNotEmpty();
            long shown = groups.stream().mapToLong(g -> ((Number) g.get("count")).longValue()).sum();
            assertThat(shown + ((Number) structured.get("elidedCount")).longValue())
                .isEqualTo(((Number) structured.get("totalDiagnostics")).longValue());
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    @Test
    void diagnosticsToolsRefuseWithoutAStoreHandle() throws Exception {
        // The store-less boot advertises both tools but a call refuses, naming the missing
        // handle: zero rows from a missing store would read identically to a clean schema.
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            for (String tool : List.of("diagnostics", "diagnostics.aggregate")) {
                var result = client.callTool(McpSchema.CallToolRequest.builder(tool).build());
                assertThat(result.isError()).as("%s refuses handle-less", tool).isTrue();
                assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                    .contains("store handle");
            }
        }
    }

    /** A server holding the fixture's live workspace and its session store handle, as the dev loop wires it. */
    private static GraphitronMcpServer server(StoreBackedBuild build) throws IOException {
        return new GraphitronMcpServer(loopback(0), build.workspace, null, null, null, null, build.handle());
    }

    // ---- directives resource ----

    @Test
    void directivesResourceIsAdvertisedAndListsBundledAndUserDeclared() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), directivesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var resources = client.listResources().resources();
            assertThat(resources).extracting(McpSchema.Resource::uri).contains("graphitron://directives");

            var read = client.readResource(McpSchema.ReadResourceRequest.builder("graphitron://directives").build());
            assertThat(read.contents()).hasSize(1);
            var text = ((McpSchema.TextResourceContents) read.contents().getFirst()).text();
            // Bundled grammar present (off vocabulary()): the @table directive ships with graphitron.
            assertThat(text).contains("@table");
            // User-declared directive present (off the live snapshot), with its applicable locations
            // rendered uniformly off the widened DirectiveShape.
            assertThat(text).contains("@guard").contains("FIELD_DEFINITION");
        }
    }

    // ---- stable-ID round-trip ----

    @Test
    @SuppressWarnings("unchecked")
    void methodRefIdsMatchTheSourceIndexJoinKeys() throws Exception {
        // The methodRef a tool emits is exactly fqcn#method/arity over the (className, name,
        // paramCount) triple the SourceWalker.MethodKey carries; pin the grammar the edges tool walks.
        try (var server = new GraphitronMcpServer(loopback(0), codeWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("conditions").build()));
            var conditions = (List<Map<String, Object>>) structured.get("conditions");
            String methodRef = (String) conditions.getFirst().get("methodRef");

            var key = new SourceWalker.MethodKey("com.example.FilmService", "activeFilms", 0);
            String fromKey = key.className() + "#" + key.methodName() + "/" + key.paramCount();
            assertThat(methodRef).isEqualTo(fromKey);
        }
    }

    // ---- edges (cross-reference traversal) ----

    @Test
    @SuppressWarnings("unchecked")
    void edgesForwardColumnFieldYieldsBacksEdgeToCatalogColumnId() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("field", "Film.title", "direction", "out")).build()));
            var node = (Map<String, Object>) structured.get("node");
            assertThat(node).containsEntry("id", "Film.title").containsEntry("kind", "field");
            var edges = (List<Map<String, Object>>) structured.get("edges");
            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e).containsEntry("kind", "BACKS").containsEntry("direction", "out");
                var target = (Map<String, Object>) e.get("target");
                // The BACKS target is the exact schema.table:column ID catalog.describe accepts.
                assertThat(target).containsEntry("id", "public.film:title").containsEntry("kind", "column");
            });
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesForwardColumnReferenceYieldsBacksPlusReferencesWithJoinPath() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("field", "Film.languageName", "direction", "out")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            assertThat(edges).extracting(e -> e.get("kind")).containsExactlyInAnyOrder("BACKS", "REFERENCES");
            // The BACKS edge lands on the terminal column; the REFERENCES edge carries the FK hop.
            var backs = edges.stream().filter(e -> e.get("kind").equals("BACKS")).findFirst().orElseThrow();
            assertThat((Map<String, Object>) backs.get("target")).containsEntry("id", "public.language:name");
            var references = edges.stream().filter(e -> e.get("kind").equals("REFERENCES")).findFirst().orElseThrow();
            assertThat((Map<String, Object>) references.get("target")).containsEntry("id", "public.language");
            var joinPath = (List<Map<String, Object>>) references.get("joinPath");
            assertThat(joinPath).singleElement().satisfies(h -> assertThat(h)
                .containsEntry("targetTableName", "public.language")
                .containsEntry("fkName", "film_language_id_fkey"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesForwardServiceBackedYieldsResolvesEdgeMatchingServicesToolRef() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("field", "Film.activeFilms", "direction", "out")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            // A table-bound @service field both resolves a method and targets a table.
            assertThat(edges).extracting(e -> e.get("kind")).containsExactlyInAnyOrder("RESOLVES", "TARGETS");
            var resolves = edges.stream().filter(e -> e.get("kind").equals("RESOLVES")).findFirst().orElseThrow();
            var target = (Map<String, Object>) resolves.get("target");
            // The method ref matches the fqcn#method/arity grammar the services / conditions tools emit.
            assertThat(target).containsEntry("id", "com.example.FilmService#activeFilms/0")
                .containsEntry("kind", "method");
            var targets = edges.stream().filter(e -> e.get("kind").equals("TARGETS")).findFirst().orElseThrow();
            assertThat((Map<String, Object>) targets.get("target")).containsEntry("id", "public.film");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesForwardNodeTypeYieldsTargetsEdgeToItsTable() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("type", "Film", "direction", "out")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e).containsEntry("kind", "TARGETS");
                assertThat((Map<String, Object>) e.get("target"))
                    .containsEntry("id", "public.film").containsEntry("kind", "table");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesReverseColumnReturnsBindingFieldCoordinates() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("column", "title", "table", "public.film", "direction", "in")).build()));
            var node = (Map<String, Object>) structured.get("node");
            assertThat(node).containsEntry("id", "public.film:title").containsEntry("kind", "column");
            var edges = (List<Map<String, Object>>) structured.get("edges");
            // The endpoint slot holds the *field*, not the queried column (direction-as-query-axis).
            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e).containsEntry("kind", "BACKS").containsEntry("direction", "in");
                assertThat((Map<String, Object>) e.get("target"))
                    .containsEntry("id", "Film.title").containsEntry("kind", "field");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesReverseMethodReturnsWiringFieldCoordinates() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("method", "com.example.FilmService#activeFilms/0", "direction", "in")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            assertThat(edges).singleElement().satisfies(e -> {
                assertThat(e).containsEntry("kind", "RESOLVES");
                assertThat((Map<String, Object>) e.get("target"))
                    .containsEntry("id", "Film.activeFilms").containsEntry("kind", "field");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesReverseTableReturnsBindingFieldsAndInboundFkNeighbours() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("table", "public.film", "direction", "in")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            // A binding field (the table-bound @service TARGETS this table) ...
            assertThat(edges).anySatisfy(e -> {
                assertThat(e).containsEntry("kind", "TARGETS");
                assertThat((Map<String, Object>) e.get("target"))
                    .containsEntry("id", "Film.activeFilms").containsEntry("kind", "field");
            });
            // ... and the inbound-FK table neighbour, read straight off the catalog.
            assertThat(edges).anySatisfy(e -> {
                assertThat(e).containsEntry("kind", "REFERENCES");
                assertThat((Map<String, Object>) e.get("target"))
                    .containsEntry("id", "public.film_actor").containsEntry("kind", "table");
            });
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesMethodWithTwoOverloadsFansOutToTwoArityDistinctResolvesEdges() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), edgesWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("field", "Film.related", "direction", "out")).build()));
            var edges = (List<Map<String, Object>>) structured.get("edges");
            assertThat(edges).allSatisfy(e -> assertThat(e).containsEntry("kind", "RESOLVES"));
            assertThat(edges).extracting(e -> ((Map<String, Object>) e.get("target")).get("id"))
                .containsExactlyInAnyOrder(
                    "com.example.FilmService#related/0", "com.example.FilmService#related/1");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesAmbiguousBareTableNameReturnsCandidateSchemas() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("table", "film")).build()));
            assertThat(structured).containsEntry("resolution", "ambiguous").containsEntry("edges", List.of());
            assertThat((List<String>) structured.get("schemas")).containsExactlyInAnyOrder("public", "other");
        }
    }

    @Test
    void edgesUnknownTableNameReturnsNotFound() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), workspaceWith(catalogFixture()));
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("table", "nope")).build()));
            assertThat(structured).containsEntry("resolution", "notFound").containsEntry("edges", List.of());
        }
    }

    @Test
    void edgesBeforeFirstBuildReportsUnavailableWithEmptyEdges() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("field", "Film.title", "direction", "in")).build()));
            assertThat(structured).containsEntry("snapshotAvailability", "Unavailable")
                .containsEntry("edges", List.of());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void edgesReverseIndexRebuildsOnNextQueryAfterBuildSwap() throws Exception {
        // The reverse index is memoised on the (snapshot, catalogFacts) reference pair; a
        // setBuildOutput swap on the same live workspace must be observed on the next reverse query.
        var workspace = edgesWorkspace();
        try (var server = new GraphitronMcpServer(loopback(0), workspace);
             var client = connect(server.port())) {
            client.initialize();

            var before = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("column", "title", "table", "public.film", "direction", "in")).build()));
            assertThat((List<Map<String, Object>>) before.get("edges")).hasSize(1);

            // Swap in a fresh build that adds a second field binding public.film:title.
            var fields = new LinkedHashMap<String, FieldClassification>();
            fields.put("Film.title", new FieldClassification.Column("film", "title"));
            fields.put("Film.altTitle", new FieldClassification.Column("film", "title"));
            var typeClassifications = Map.<String, TypeClassification>of(
                "Film", new TypeClassification.Node("film", "FilmType", List.of("film_id")));
            var snapshot = new LspSchemaSnapshot.Built.Current(
                List.of(), Map.of(), Map.of(), fields, typeClassifications, Map.of());
            workspace.setBuildOutput(
                new GraphQLRewriteGenerator.BuildArtifacts(edgesCatalog(), snapshot, edgesFacts()),
                ValidationReport.empty());

            var after = structured(client.callTool(McpSchema.CallToolRequest.builder("edges")
                .arguments(Map.of("column", "title", "table", "public.film", "direction", "in")).build()));
            assertThat((List<Map<String, Object>>) after.get("edges"))
                .as("the memo key missed on the new snapshot, so the index rebuilt with the added field")
                .extracting(e -> ((Map<String, Object>) e.get("target")).get("id"))
                .containsExactlyInAnyOrder("Film.title", "Film.altTitle");
        }
    }

    // ---- docs.search (semantic retrieval over the bundled manual) ----

    @Test
    @SuppressWarnings("unchecked")
    void docsSearchWhileWarmingReturnsDegradationMessageAndNoPassages() throws Exception {
        // Un-started warms read as Warming: the tool is advertised but degrades to the structured tools.
        var embedderWarm = new AsyncWarm<Embedder>("e", () -> new PlantedEmbedder(3, new float[] {1, 0, 0}));
        var docsWarm = new AsyncWarm<DocsIndex>("d", GraphitronMcpServerTest::pagingDocsIndex);
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), embedderWarm, docsWarm);
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("docs.search")
                .arguments(Map.of("query", "how do I paginate")).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("still warming");
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat((List<Map<String, Object>>) structured.get("passages")).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void docsSearchWhenEmbedderWarmFailedReturnsDegradationNamingTheCause() throws Exception {
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> {
            throw new RuntimeException("ONNX unavailable");
        }));
        var docsWarm = startedAwaited(new AsyncWarm<DocsIndex>("d", GraphitronMcpServerTest::pagingDocsIndex));
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), embedderWarm, docsWarm);
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("docs.search")
                .arguments(Map.of("query", "anything")).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("failed to load").contains("ONNX unavailable");
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat((List<Map<String, Object>>) structured.get("passages")).isEmpty();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void docsSearchReadyReturnsRankedPassagesWithHeadingPathSourceUrlAndScore() throws Exception {
        // The query embedder lands on the keyset passage's planted vector; KNN puts it first.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e",
            () -> new PlantedEmbedder(3, new float[] {1, 0, 0})));
        var docsWarm = startedAwaited(new AsyncWarm<DocsIndex>("d", GraphitronMcpServerTest::pagingDocsIndex));
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), embedderWarm, docsWarm);
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("docs.search")
                .arguments(Map.of("query", "stable cursors", "k", 2)).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            var structured = (Map<String, Object>) result.structuredContent();
            var passages = (List<Map<String, Object>>) structured.get("passages");
            assertThat(passages).isNotEmpty();
            var top = passages.getFirst();
            assertThat((List<String>) top.get("headingPath")).containsExactly("Batching", "Keyset seek");
            assertThat(top).containsEntry("sourcePath", "docs/manual/explanation/batching-model.adoc")
                .containsEntry("anchor", "keyset-seek")
                .containsEntry("url",
                    "https://graphitron.sikt.no/manual/explanation/batching-model.html#keyset-seek");
            assertThat((Double) top.get("score")).isGreaterThan(0.0);
            // The text summary names the top hit's heading path for non-structured clients.
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("Batching > Keyset seek");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void docsSearchDimensionMismatchDegradesRatherThanThrowingLuceneWidthError() throws Exception {
        // The bundle was embedded at dimension 4; the runtime embedder produces width 3. The guard
        // degrades rather than letting the KNN query reach Lucene with a mismatched width.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e",
            () -> new PlantedEmbedder(3, new float[] {1, 0, 0})));
        var docsWarm = startedAwaited(new AsyncWarm<DocsIndex>("d", () -> docsIndexOf(4, List.of(
            entry(new DocChunk(List.of("A"), "docs/manual/x.adoc", "a", "body"), new float[] {1, 0, 0, 0})))));
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace(), embedderWarm, docsWarm);
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("docs.search")
                .arguments(Map.of("query", "anything")).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(((McpSchema.TextContent) result.content().getFirst()).text())
                .contains("different embedding model");
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat((List<Map<String, Object>>) structured.get("passages")).isEmpty();
        }
    }

    /** A two-chunk docs index (dim 3): a keyset-seek passage at [1,0,0] and a batching one at [0,1,0]. */
    private static DocsIndex pagingDocsIndex() {
        return docsIndexOf(3, List.of(
            entry(new DocChunk(List.of("Batching", "Keyset seek"),
                "docs/manual/explanation/batching-model.adoc", "keyset-seek",
                "Use keyset pagination for stable cursors."), new float[] {1, 0, 0}),
            entry(new DocChunk(List.of("Batching", "Data loader"),
                "docs/manual/explanation/batching-model.adoc", "data-loader",
                "The data loader batches sibling fetches."), new float[] {0, 1, 0})));
    }

    private static DocsBundle.Entry entry(DocChunk chunk, float[] vector) {
        return new DocsBundle.Entry(chunk.id(), chunk.embedText(), DocsBundle.encodePayload(chunk), vector);
    }

    /** Writes the entries to a bundle and rebuilds the index through the production warm loader. */
    private static DocsIndex docsIndexOf(int dimension, List<DocsBundle.Entry> entries) {
        var buf = new ByteArrayOutputStream();
        DocsBundle.write(buf, dimension, entries);
        return DocsRag.loadDocsIndex(new ByteArrayInputStream(buf.toByteArray()));
    }

    private static <T> AsyncWarm<T> startedAwaited(AsyncWarm<T> warm) {
        warm.start();
        warm.await();
        return warm;
    }

    /** A query-side embedder that returns a fixed planted vector; documents come prebuilt in the bundle. */
    private record PlantedEmbedder(int dim, float[] queryVector) implements Embedder {
        @Override
        public Query embedQuery(String text) {
            return new Query(text, queryVector);
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            throw new UnsupportedOperationException("documents are prebuilt in the bundle");
        }

        @Override
        public int dimension() {
            return dim;
        }
    }

    // ---- catalog.search (semantic catalog discovery) ----

    @Test
    @SuppressWarnings("unchecked")
    void catalogSearchReturnsRankedTableIdsWhoseTopFeedsCatalogDescribe() throws Exception {
        // The shared embedder warm carries a fake (no ONNX); BM25 over the descriptors carries the
        // ranking. The server kicks the index warm at bind; awaitRagWarm() waits it out deterministically.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> new FakeEmbedder(384)));
        try (var server = new GraphitronMcpServer(
                loopback(0), catalogSearchWorkspace(), embedderWarm, null, RagConfig.temporary());
             var client = connect(server.port())) {
            client.initialize();
            server.awaitRagWarm();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.search")
                .arguments(Map.of("query", "customer address")).build()));
            assertThat(structured).containsEntry("status", "ready");
            var results = (List<Map<String, Object>>) structured.get("results");
            assertThat(results).isNotEmpty();

            // Each hit carries the schema-qualified id catalog.describe accepts, split into schema / name.
            var top = results.get(0);
            assertThat(top).containsKeys("id", "schema", "name", "score");
            String topId = (String) top.get("id");
            assertThat(topId).isEqualTo(top.get("schema") + "." + top.get("name"));

            // Discovery hands off to description: the top id feeds straight into catalog.describe.
            var describe = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.describe")
                .arguments(Map.of("table", topId)).build()));
            assertThat(describe).containsEntry("resolution", "resolved");
        }
    }

    @Test
    void catalogSearchReportsWarmingWhileTheIndexIsStillBuilding() throws Exception {
        // A blocking embedder pins the index in Warming; the first search reports the degradation.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> new BlockingEmbedder(384)));
        try (var server = new GraphitronMcpServer(
                loopback(0), catalogSearchWorkspace(), embedderWarm, null, RagConfig.temporary());
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("catalog.search")
                .arguments(Map.of("query", "customer address")).build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(result.content()).isNotEmpty();
            @SuppressWarnings("unchecked")
            var structured = (Map<String, Object>) result.structuredContent();
            assertThat(structured).containsEntry("status", "warming");
        }
    }

    /** A two-table fixture (address, customer) the catalog.search BM25 ranking discovers by name. */
    private static Workspace catalogSearchWorkspace() {
        var address = new CatalogFacts.Table(
            "public", "address", Optional.of("Customer mailing addresses"),
            List.of(
                new CatalogFacts.Column("address_id", "ADDRESS_ID", "integer", false, Optional.empty()),
                new CatalogFacts.Column("address", "ADDRESS", "varchar", false, Optional.of("Street address"))),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var customer = new CatalogFacts.Table(
            "public", "customer", Optional.empty(),
            List.of(new CatalogFacts.Column("customer_id", "CUSTOMER_ID", "integer", false, Optional.empty())),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var map = new LinkedHashMap<String, CatalogFacts.Table>();
        map.put("public.address", address);
        map.put("public.customer", customer);
        return workspaceWith(new CatalogFacts(map));
    }

    /** An {@link Embedder} that blocks forever in {@code embedDocuments}, pinning the index in Warming. */
    private static final class BlockingEmbedder implements Embedder {
        private final int dimension;
        private final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);

        BlockingEmbedder(int dimension) {
            this.dimension = dimension;
        }

        @Override
        public Query embedQuery(String text) {
            return new Query(text, no.sikt.graphitron.mcp.rag.FakeEmbedder.oneHot(text, dimension));
        }

        @Override
        public List<Embedding> embedDocuments(List<String> texts) {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            return texts.stream()
                .map(t -> new Embedding(t, no.sikt.graphitron.mcp.rag.FakeEmbedder.oneHot(t, dimension)))
                .toList();
        }

        @Override
        public int dimension() {
            return dimension;
        }
    }

    /**
     * A single-schema fixture wiring a {@code Film} {@code @node} type to {@code public.film}, with a
     * column field, a {@code @reference} column field reaching {@code public.language}, a table-bound
     * {@code @service} field, and a two-overload {@code @service} field. The catalog carries the
     * matching tables and FKs so forward edges land on real catalog IDs and the reverse index
     * inverts them.
     */
    private static Workspace edgesWorkspace() {
        var fields = new LinkedHashMap<String, FieldClassification>();
        fields.put("Film.title", new FieldClassification.Column("film", "title"));
        fields.put("Film.languageName", new FieldClassification.ColumnReference("language", "name",
            List.of(new FieldClassification.FkStep("public.language", "film_language_id_fkey"))));
        fields.put("Film.activeFilms",
            new FieldClassification.ServiceBacked("com.example.FilmService", "activeFilms", true, "film", null));
        fields.put("Film.related",
            new FieldClassification.ServiceBacked("com.example.FilmService", "related", false, null, null));
        var typeClassifications = Map.<String, TypeClassification>of(
            "Film", new TypeClassification.Node("film", "FilmType", List.of("film_id")));
        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(), fields, typeClassifications, Map.of());
        var workspace = new Workspace();
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(edgesCatalog(), snapshot, edgesFacts()),
            ValidationReport.empty());
        return workspace;
    }

    /** The external-reference scan the edges fixture reconciles method arities against. */
    private static CompletionData edgesCatalog() {
        var filmService = new CompletionData.ExternalReference(
            "com.example.FilmService", "com.example.FilmService", "",
            List.of(
                new CompletionData.Method("activeFilms", "List", "", List.of(), false),
                new CompletionData.Method("related", "List", "", List.of(), false),
                new CompletionData.Method("related", "List", "",
                    List.of(new CompletionData.Parameter("limit", "int", "Arg", "")), false)),
            List.of());
        return new CompletionData(List.of(), List.of(), List.of(filmService), Map.of());
    }

    /** {@code public.film} (+ outbound FK to language, inbound FK from film_actor), language, film_actor. */
    private static CatalogFacts edgesFacts() {
        var film = new CatalogFacts.Table(
            "public", "film", Optional.empty(),
            List.of(
                new CatalogFacts.Column("film_id", "FILM_ID", "integer", false, Optional.empty()),
                new CatalogFacts.Column("title", "TITLE", "varchar", false, Optional.empty()),
                new CatalogFacts.Column("language_id", "LANGUAGE_ID", "integer", false, Optional.empty())),
            Optional.of(new CatalogFacts.Key("film_pkey", List.of("film_id"))),
            List.of(), List.of(),
            new CatalogFacts.ForeignKeys(
                List.of(new CatalogFacts.OutgoingForeignKey(
                    "film_language_id_fkey", "public.language", List.of("language_id"), List.of("language_id"))),
                List.of(new CatalogFacts.IncomingForeignKey(
                    "film_actor_film_id_fkey", "public.film_actor", List.of("film_id"), List.of("film_id")))));
        var language = new CatalogFacts.Table(
            "public", "language", Optional.empty(),
            List.of(
                new CatalogFacts.Column("language_id", "LANGUAGE_ID", "integer", false, Optional.empty()),
                new CatalogFacts.Column("name", "NAME", "varchar", false, Optional.empty())),
            Optional.of(new CatalogFacts.Key("language_pkey", List.of("language_id"))),
            List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var filmActor = new CatalogFacts.Table(
            "public", "film_actor", Optional.empty(),
            List.of(new CatalogFacts.Column("film_id", "FILM_ID", "integer", false, Optional.empty())),
            Optional.empty(), List.of(), List.of(), CatalogFacts.ForeignKeys.empty());
        var map = new LinkedHashMap<String, CatalogFacts.Table>();
        map.put("public.film", film);
        map.put("public.language", language);
        map.put("public.film_actor", filmActor);
        return new CatalogFacts(map);
    }

    private static Workspace codeWorkspace() {
        var service = new CompletionData.ExternalReference(
            "com.example.FilmService", "com.example.FilmService", "",
            List.of(
                new CompletionData.Method("list", "Film", "", List.of(), false),
                new CompletionData.Method("activeFilms", "Condition", "", List.of(), true)),
            List.of());
        var card = new CompletionData.ExternalReference(
            "com.example.FilmCard", "com.example.FilmCard", "",
            List.of(),
            List.of(new CompletionData.RecordComponent("filmId", "Integer"),
                new CompletionData.RecordComponent("title", "String")));
        var catalog = new CompletionData(List.of(), List.of(), List.of(service, card), Map.of());
        var workspace = builtWorkspace(catalog, new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of()),
            ValidationReport.empty());
        // FilmService class + its activeFilms method are indexed; FilmCard is not (not-yet-indexed arm).
        var classes = Map.of("com.example.FilmService",
            new SourceWalker.Decl(new CompletionData.SourceLocation("file:///src/FilmService.java", 4, 0), ""));
        var methods = Map.of(
            new SourceWalker.MethodKey("com.example.FilmService", "activeFilms", 0),
            new SourceWalker.Decl(new CompletionData.SourceLocation("file:///src/FilmService.java", 10, 4), ""));
        workspace.setSourceIndex(new SourceWalker.Index(classes, methods, Map.of(), Set.of()));
        return workspace;
    }

    private static Workspace schemaWorkspace() {
        var typeClassifications = new LinkedHashMap<String, TypeClassification>();
        typeClassifications.put("Film", new TypeClassification.Node("film", "FilmType", List.of("film_id")));
        typeClassifications.put("Actor", new TypeClassification.Table("actor"));
        Map<String, TypeBackingShape> backing = Map.of("Film", new TypeBackingShape.TableBacking("film"));
        Map<String, FieldClassification> fields = Map.of("Film.title", new FieldClassification.Column("film", "title"));
        Map<String, CompletionData.SourceLocation> locations =
            Map.of("Film", new CompletionData.SourceLocation("file:///schema.graphqls", 3, 0));
        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(), backing, Map.of(), fields, typeClassifications, locations);
        // @node metadata rides the catalog (the snapshot has no @node projection).
        var catalog = new CompletionData(List.of(), List.of(), List.of(),
            Map.of("Film", new CompletionData.NodeMetadata("FilmType", List.of("film_id"))));
        return builtWorkspace(catalog, snapshot, ValidationReport.empty());
    }

    private static Workspace conflictedWorkspace() {
        var conflicted = new FieldClassification.Conflicted(List.of(
            new FieldClassification.Claim.Service("com.example.FilmService", "delete", "service", true, null),
            new FieldClassification.Claim.Mutation("DELETE", "film", "mutation", true,
                new CompletionData.SourceLocation("file:///schema.graphqls", 11, 2))),
            "@service, @mutation are mutually exclusive");
        var chained = new FieldClassification.Conflicted(List.of(
            new FieldClassification.Claim.Service("com.example.FilmService", "run", "service", true, null),
            new FieldClassification.Claim.Routine(List.of("first_fn", "second_fn"), "routine", true, null)),
            "@service, @routine are mutually exclusive");
        Map<String, FieldClassification> fields = Map.of(
            "Mutation.deleteFilm", conflicted,
            "Mutation.broken", new FieldClassification.Unresolvable("no matching classification rule"),
            "Query.films", chained);
        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(), fields,
            Map.of("Mutation", new TypeClassification.Root("mutation"),
                "Query", new TypeClassification.Root("query")), Map.of());
        return builtWorkspace(CompletionData.empty(), snapshot, ValidationReport.empty());
    }

    private static Workspace directivesWorkspace() {
        // A user-declared directive lands in the snapshot's directive surface with its applicable
        // locations carried (the DirectiveShape widening), via the production buildSnapshot path.
        var registry = new graphql.schema.idl.SchemaParser().parse("""
            directive @guard(role: String!) on OBJECT | FIELD_DEFINITION
            type Query { x: Int }
            """);
        var snapshot = no.sikt.graphitron.rewrite.catalog.CatalogBuilder.buildSnapshot(registry);
        return builtWorkspace(CompletionData.empty(), snapshot, ValidationReport.empty());
    }

    private static Workspace builtWorkspace(
        CompletionData catalog, LspSchemaSnapshot.Built.Current snapshot, ValidationReport report
    ) {
        var workspace = new Workspace();
        workspace.setBuildOutput(
            new GraphQLRewriteGenerator.BuildArtifacts(catalog, snapshot), report);
        return workspace;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(result.structuredContent()).isInstanceOf(Map.class);
        return (Map<String, Object>) result.structuredContent();
    }

    /** The summary line an agent reads before the structured payload; the leading count lives here. */
    private static String firstLine(McpSchema.CallToolResult result) {
        assertThat(result.content()).isNotEmpty();
        return ((McpSchema.TextContent) result.content().getFirst()).text().lines().findFirst().orElse("");
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
