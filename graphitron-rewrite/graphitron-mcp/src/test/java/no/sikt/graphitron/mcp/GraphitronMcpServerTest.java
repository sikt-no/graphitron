package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import no.sikt.graphitron.rewrite.model.Rejection;
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
import java.util.Set;

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
                .containsExactlyInAnyOrder("status", "catalog.tables", "catalog.describe",
                    "services", "conditions", "records", "schema", "diagnostics");

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

    // ---- R368: services / conditions / records ----

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

    // ---- R368: schema ----

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
    void schemaReportsUnavailableBeforeFirstBuild() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("schema").build()));
            assertThat(structured).containsEntry("availability", "Unavailable").containsEntry("types", List.of());
        }
    }

    // ---- R368: diagnostics ----

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsReturnsMappedErrorsAndReportsSnapshotFreshness() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), diagnosticsWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics").build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).hasSize(2);
            assertThat(diagnostics.get(0))
                .containsEntry("severity", "error")
                .containsEntry("coordinate", "Query.film")
                .containsEntry("message", "unknown table reference")
                .containsEntry("rejectionKind", "author-error");
            var location = (Map<String, Object>) diagnostics.get(0).get("location");
            assertThat(location).containsEntry("line", 4).containsEntry("column", 2);
            assertThat(diagnostics.get(1)).containsEntry("severity", "warning")
                .containsEntry("message", "shadowed directive");
            // Snapshot axes reported alongside so an agent can tell whether diagnostics are current.
            assertThat(structured).containsEntry("snapshotAvailability", "Built")
                .containsEntry("snapshotFreshness", "Current");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void diagnosticsFiltersBySeverity() throws Exception {
        try (var server = new GraphitronMcpServer(loopback(0), diagnosticsWorkspace());
             var client = connect(server.port())) {
            client.initialize();

            var structured = structured(client.callTool(McpSchema.CallToolRequest.builder("diagnostics")
                .arguments(Map.of("severity", "error")).build()));
            var diagnostics = (List<Map<String, Object>>) structured.get("diagnostics");
            assertThat(diagnostics).singleElement().satisfies(d -> assertThat(d).containsEntry("severity", "error"));
        }
    }

    // ---- R368: directives resource ----

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

    // ---- R368: stable-ID round-trip ----

    @Test
    @SuppressWarnings("unchecked")
    void methodRefIdsMatchTheSourceIndexJoinKeys() throws Exception {
        // The methodRef a tool emits is exactly fqcn#method/arity over the (className, name,
        // paramCount) triple the SourceWalker.MethodKey carries; pin that grammar slice 7 will walk.
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
        var catalog = new CompletionData(List.of(), List.of(), List.of(service, card), Map.of(), Map.of());
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
        var catalog = new CompletionData(List.of(), List.of(), List.of(), Map.of(),
            Map.of("Film", new CompletionData.NodeMetadata("FilmType", List.of("film_id"))));
        return builtWorkspace(catalog, snapshot, ValidationReport.empty());
    }

    private static Workspace diagnosticsWorkspace() {
        var error = new ValidationError("Query.film",
            new Rejection.AuthorError.Structural("unknown table reference"),
            new graphql.language.SourceLocation(5, 3, "/schema.graphqls"));
        var warning = new BuildWarning("shadowed directive",
            new graphql.language.SourceLocation(1, 1, "/schema.graphqls"));
        var report = ValidationReport.from(List.of(error), List.of(warning));
        return builtWorkspace(CompletionData.empty(),
            new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of()), report);
    }

    private static Workspace directivesWorkspace() {
        // A user-declared directive lands in the snapshot's directive surface with its applicable
        // locations carried (R368 DirectiveShape widening), via the production buildSnapshot path.
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
