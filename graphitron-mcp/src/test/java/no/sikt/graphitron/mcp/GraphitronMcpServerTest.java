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
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
        // the freshness axis is absent, not null-valued. That this tool is advertised at all is
        // asserted where the whole advertised surface is named, in ServerInstructionsTest, rather
        // than by a second hardcoded list here that would have to be edited twice.
        try (var server = new GraphitronMcpServer(loopback(0), new Workspace());
             var client = connect(server.port())) {
            client.initialize();

            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("status");

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
        // with no dev database the execute tool is simply absent (pinned by the named surface in
        // ServerInstructionsTest, which boots both arms); with one configured it appears.
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

    // These read the census alone, so they capture it directly rather than running a build for it: the
    // catalog walk is a function of the generated jOOQ model, and no SDL the cases could declare would
    // change which tables it writes.

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
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var structured = structured(
                GraphitronMcpServer.catalogTablesResult(census.handle(), Map.of()));

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
        try (var census = StoreFixture.ofCatalog(tmp)) {
            // The schema filter is exact and case-insensitive; every test table lives in public.
            var bySchema = structured(GraphitronMcpServer.catalogTablesResult(
                census.handle(), Map.of("schema", "PUBLIC")));
            @SuppressWarnings("unchecked")
            var inPublic = (List<Map<String, Object>>) bySchema.get("tables");
            assertThat(inPublic).isNotEmpty();
            assertThat(inPublic).extracting(t -> t.get("schema")).containsOnly("public");

            var noSuchSchema = structured(GraphitronMcpServer.catalogTablesResult(
                census.handle(), Map.of("schema", "other")));
            @SuppressWarnings("unchecked")
            var elsewhere = (List<Map<String, Object>>) noSuchSchema.get("tables");
            assertThat(elsewhere).isEmpty();

            // The name filter is a case-insensitive substring, so it reaches every table carrying it.
            var byName = structured(GraphitronMcpServer.catalogTablesResult(
                census.handle(), Map.of("name", "ACT")));
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
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var unpaged = structured(GraphitronMcpServer.catalogTablesResult(census.handle(), Map.of()));
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
                var result = GraphitronMcpServer.catalogTablesResult(census.handle(), args);
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

    /**
     * The wire fields are the shipped ones; what changed is that they come from the census and that
     * the columns arrive in the table definition's order rather than a reflective field walk's, which
     * is documented as no order in particular.
     *
     * <p>The column {@code comment} slot is asserted on both arms from the fixture's own DDL, which is
     * what makes it a test rather than a restatement of a mock: {@code film_id} declares one and
     * {@code release_year} deliberately declares none. One of them carries an apostrophe on purpose,
     * that being the character a naive pipeline breaks on, and {@code description} is a column named
     * for the thing it carries so no reader can conflate the two.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogDescribeReadsOneTablesWholeDescriptionFromTheCensus(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var structured = structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "public.film")));

            assertThat(structured).containsEntry("resolution", "resolved")
                .containsEntry("schema", "public").containsEntry("name", "film")
                .containsEntry("comment", "One film in the rental catalogue.");

            var columns = (List<Map<String, Object>>) structured.get("columns");
            assertThat(columns).extracting(c -> (String) c.get("sqlName"))
                .as("the table definition's order, which is what sql_column.ordinal states")
                .startsWith("film_id", "title", "description", "release_year", "language_id");
            assertThat(columns.getFirst())
                .containsEntry("javaName", "FILM_ID").containsEntry("sqlType", "integer")
                .containsEntry("nullable", false)
                .containsEntry("comment", "Surrogate key, stable across catalogue imports.");
            assertThat(columnNamed(columns, "title"))
                .containsEntry("comment", "Display title, as printed on the distributor's case.");
            assertThat(columnNamed(columns, "description"))
                .as("a column named description carries its own comment, not its name")
                .containsEntry("comment", "Free-text synopsis shown to renters.");
            assertThat(columnNamed(columns, "release_year"))
                .as("the database declares no comment here, so the slot is absent rather than blank")
                .doesNotContainKey("comment")
                .containsEntry("nullable", true);

            assertThat((Map<String, Object>) structured.get("primaryKey"))
                .containsEntry("constraintName", "film_pkey")
                .containsEntry("columns", List.of("film_id"));

            var foreignKeys = (Map<String, Object>) structured.get("foreignKeys");
            var outgoing = (List<Map<String, Object>>) foreignKeys.get("outgoing");
            assertThat(outgoing)
                .as("both language references, each naming its target by the full table id")
                .allSatisfy(fk -> assertThat(fk).containsEntry("targetTable", "public.language"))
                .extracting(fk -> fk.get("columns"))
                .contains(List.of("language_id"), List.of("original_language_id"));
            var incoming = (List<Map<String, Object>>) foreignKeys.get("incoming");
            assertThat(incoming).extracting(fk -> (String) fk.get("sourceTable"))
                .contains("public.film_actor", "public.inventory");
        }
    }

    /**
     * Every unique constraint the database declares is reported, including one whose columns the
     * primary key already covers. The projection this replaced deduplicated on column set, which was
     * a row-identity consumer's key-matching rule applied to a discovery tool: that consumer wants
     * distinct column sets and dedups for itself, and a description that dedupped could not tell an
     * agent what the database actually declares.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogDescribeReportsAUniqueKeyThePrimaryKeyAlreadyCovers(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var structured = structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "redundant_unique_key")));

            assertThat((Map<String, Object>) structured.get("primaryKey"))
                .containsEntry("constraintName", "redundant_unique_key_pkey")
                .containsEntry("columns", List.of("entry_id"));
            assertThat((List<Map<String, Object>>) structured.get("uniqueKeys"))
                .as("the covered constraint is a declaration, not a duplicate to be filtered")
                .singleElement()
                .satisfies(key -> assertThat(key)
                    .containsEntry("constraintName", "redundant_unique_key_entry_id_uk")
                    .containsEntry("columns", List.of("entry_id")));
        }
    }

    /**
     * A multi-column foreign key's two column lists pair up, and pair up in the referenced
     * constraint's own order. The census never copies the target columns onto the referencing row,
     * they being the referenced constraint's own rows matched on position, so this is the one place a
     * hand-written pairing could go wrong where the relation could not: a join that forgot the
     * position predicate would answer with every combination of the two lists and still look like a
     * foreign key.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogDescribePairsAMultiColumnForeignKeyByPosition(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var structured = structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "public.project_note")));

            var foreignKeys = (Map<String, Object>) structured.get("foreignKeys");
            assertThat((List<Map<String, Object>>) foreignKeys.get("outgoing"))
                .singleElement()
                .satisfies(fk -> assertThat(fk)
                    .containsEntry("constraintName", "project_note_project_fkey")
                    .containsEntry("targetTable", "public.project")
                    .containsEntry("columns", List.of("org_id", "project_id"))
                    .containsEntry("targetColumns", List.of("org_id", "project_id")));
        }
    }

    /**
     * Every list a description carries is non-empty at once, which is the case a mis-correlated nested
     * projection shows up in. The description is one query whose child lists are subqueries correlated
     * to the table row, and the way that shape fails is a child landing on the wrong parent: an index
     * carrying the other index's columns, a key carrying the other key's, the incoming keys appearing
     * among the outgoing. Each of those is a list that still reads as an answer, so nothing catches it
     * unless every slot has something in it to be confused with.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogDescribeCorrelatesEveryNestedListToItsOwnParent(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            var structured = structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "public.describe_hub")));

            assertThat(structured).containsEntry("resolution", "resolved");
            assertThat((List<Map<String, Object>>) structured.get("columns"))
                .extracting(c -> (String) c.get("sqlName"))
                .containsExactly("hub_id", "hub_code", "org_id", "project_id", "label");

            assertThat((Map<String, Object>) structured.get("primaryKey"))
                .containsEntry("constraintName", "describe_hub_pkey")
                .containsEntry("columns", List.of("hub_id"));
            assertThat((List<Map<String, Object>>) structured.get("uniqueKeys"))
                .as("the unique constraint carries its own column, not the primary key's beside it")
                .singleElement()
                .satisfies(key -> assertThat(key)
                    .containsEntry("constraintName", "describe_hub_hub_code_uk")
                    .containsEntry("columns", List.of("hub_code")));

            assertThat((List<Map<String, Object>>) structured.get("indexes"))
                .as("the two declared indexes, each with its own columns in index order, and neither "
                    + "constraint's backing index")
                .containsExactly(
                    Map.of("name", "describe_hub_label_idx", "columns", List.of("label")),
                    Map.of("name", "describe_hub_org_label_idx",
                        "columns", List.of("org_id", "label")));

            var foreignKeys = (Map<String, Object>) structured.get("foreignKeys");
            assertThat((List<Map<String, Object>>) foreignKeys.get("outgoing"))
                .as("the key this table declares, and not the leaf's key beside it")
                .singleElement()
                .satisfies(fk -> assertThat(fk)
                    .containsEntry("constraintName", "describe_hub_project_fkey")
                    .containsEntry("targetTable", "public.project")
                    .containsEntry("columns", List.of("org_id", "project_id"))
                    .containsEntry("targetColumns", List.of("org_id", "project_id")));
            assertThat((List<Map<String, Object>>) foreignKeys.get("incoming"))
                .as("the key the leaf declares against this table, reported by what declares it")
                .singleElement()
                .satisfies(fk -> assertThat(fk)
                    .containsEntry("constraintName", "describe_hub_leaf_hub_fkey")
                    .containsEntry("sourceTable", "public.describe_hub_leaf")
                    .containsEntry("columns", List.of("hub_id"))
                    .containsEntry("targetColumns", List.of("hub_id")));
        }
    }

    /**
     * A bare spelling two schemas declare names the candidates instead of picking one. This is the
     * case a capture from the single-schema package cannot produce at all, every name there being
     * unique, so it reads a census the fixture module generates from two schemas that declare
     * {@code event} between them for exactly this purpose.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogDescribeNamesTheCandidatesForASpellingTwoSchemasDeclare(@TempDir Path tmp) {
        try (var census = StoreFixture.ofMultiSchemaCatalog(tmp)) {
            var result = GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "event"));

            assertThat(structured(result)).containsEntry("resolution", "ambiguous");
            assertThat((List<String>) structured(result).get("schemas"))
                .containsExactly("multischema_a", "multischema_b");
            assertThat(firstLine(result))
                .as("the summary tells an agent how to re-call rather than only that it failed")
                .contains("multischema_a.event");

            // Qualifying it resolves, which is what the candidate list is for.
            assertThat(structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "multischema_b.event"))))
                .containsEntry("resolution", "resolved")
                .containsEntry("schema", "multischema_b");
        }
    }

    @Test
    void catalogDescribeReturnsNotFoundForUnknownName(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            assertThat(structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "nope"))))
                .containsEntry("resolution", "notFound").containsEntry("table", "nope");

            // A spelling with an empty half names nothing the census could hold, and is answered
            // without opening a transaction to find that out.
            assertThat(structured(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), census.reader(), Map.of("table", "public."))))
                .containsEntry("resolution", "notFound");
        }
    }

    /**
     * The refusal gate is the reader, that being what this tool answers through. A store-less server
     * refuses rather than reporting a table it could not look for, on the same grounds
     * {@code catalog.tables} refuses: an empty answer reads as a fact about the database.
     */
    @Test
    void catalogDescribeRefusesWithoutAStoreToRead(@TempDir Path tmp) {
        try (var census = StoreFixture.ofCatalog(tmp)) {
            assertThat(GraphitronMcpServer.catalogDescribeResult(
                census.handle(), null, Map.of("table", "film")).isError()).isTrue();
            assertThat(firstLine(GraphitronMcpServer.catalogDescribeResult(
                null, census.reader(), Map.of("table", "film"))))
                .startsWith("catalog.describe:")
                .contains("holds no fact store handle");
        }
    }

    /** The entry for one column by its SQL name, the census's order being the table definition's. */
    private static Map<String, Object> columnNamed(List<Map<String, Object>> columns, String sqlName) {
        return columns.stream().filter(c -> sqlName.equals(c.get("sqlName"))).findFirst()
            .orElseThrow(() -> new AssertionError("no column named " + sqlName));
    }

    // ---- code ----

    // One census, three predicates over it. Every case below reads a store captured from this module's
    // own fixture sources: a real classfile scan for the census and a real parse for the declaration
    // family, over the same code, which is what makes the descriptors, the Condition match, the
    // declared type forms and a record's mandated members the ones a consumer's compiler produces.

    /**
     * The service kind: the class and its methods, with the method-ref grammar the instructions promise
     * and the class's own source location and doc comment. What replaces the shipped {@code services}
     * case, at the same grain, off relations instead of a scan.
     *
     * <p>The parameter's {@code name} is absent because this module compiles without
     * {@code -parameters}, which is the census's documented NULL arm and the wire's stated contract:
     * omit rather than synthesise a positional stand-in. Its {@code type} is the declared form, so the
     * element type the erasure drops survives to the wire.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeServiceKindListsAClassWithItsMethodsAndItsDeclaration(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "service", FILM_SERVICE);

            assertThat(entry).containsEntry("classRef", FILM_SERVICE).containsEntry("className", FILM_SERVICE);
            assertThat((String) entry.get("description"))
                .as("the doc comment the parse retained, off the declaration the location came from")
                .startsWith("A service host the code tool reads.");
            assertThat((Map<String, Object>) entry.get("location"))
                .containsEntry("uri", fixtureUri("FilmService.java"))
                .containsEntry("line", fixtureLine("FilmService.java", "public class FilmService"));

            var methods = (List<Map<String, Object>>) entry.get("methods");
            assertThat(methods).extracting(m -> m.get("methodRef"))
                .as("every public method, condition ones included, ordered by name then descriptor")
                .containsExactly(
                    FILM_SERVICE + "#activeFilms/0",
                    FILM_SERVICE + "#describe/1",
                    FILM_SERVICE + "#describe/1",
                    FILM_SERVICE + "#titles/1");

            assertThat(methodNamed(methods, "titles")).satisfies(m -> {
                assertThat(m).containsEntry("returnType", "List<String>");
                var parameters = (List<Map<String, Object>>) m.get("parameters");
                assertThat(parameters).singleElement().satisfies(p -> assertThat(p)
                    .containsEntry("type", "int")
                    .doesNotContainKey("name"));
                assertThat((String) m.get("description"))
                    .startsWith("The titles of at most the given number of films.");
                assertThat((Map<String, Object>) m.get("location"))
                    .containsEntry("line", fixtureLine("FilmService.java", "public List<String> titles"));
            });

            assertThat((List<Map<String, Object>>) entry.get("components")).isEmpty();
        }
    }

    /**
     * The condition kind: the same class, with the method list narrowed to the methods whose return type
     * is a jOOQ {@code Condition}. The kind is the only one that narrows what the entry carries, being
     * the only one that names a method population rather than a class one.
     *
     * <p>The match is on the un-erased descriptor at capture, so this asserts what the store holds
     * rather than a name comparison: {@code titles} and both {@code describe} overloads drop out.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeConditionKindNarrowsTheMethodListToTheConditionMethods(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "condition", FILM_SERVICE);

            var methods = (List<Map<String, Object>>) entry.get("methods");
            assertThat(methods).singleElement().satisfies(m -> {
                assertThat(m).containsEntry("methodRef", FILM_SERVICE + "#activeFilms/0")
                    .containsEntry("name", "activeFilms")
                    .containsEntry("returnType", "Condition");
                assertThat((String) m.get("description")).startsWith("Films still on the shelf.");
                assertThat((Map<String, Object>) m.get("location"))
                    .containsEntry("uri", fixtureUri("FilmService.java"))
                    .containsEntry("line", fixtureLine("FilmService.java", "public Condition activeFilms"));
            });
        }
    }

    /**
     * The record kind: the components in declaration order, with their declared types.
     *
     * <p>And the arm the shipped fixture could not express, because it declared a record with no
     * methods and a real record has five. Every one of them is {@code notDeclared}: the classfile
     * carries the accessors and the mandated {@code equals} / {@code hashCode} / {@code toString}, this
     * record's source declares none of them, and the two populations are documented as allowed to
     * disagree. The class itself still resolves, which is the whole point of keeping the two absences
     * apart: nothing here is stale, and re-walking the source would change nothing.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeRecordKindListsComponentsAndReportsMandatedMembersAsUndeclared(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "record", FILM_CARD);

            var components = (List<Map<String, Object>>) entry.get("components");
            assertThat(components).containsExactly(
                Map.of("name", "filmId", "type", "Integer"),
                Map.of("name", "title", "type", "String"));

            assertThat((Map<String, Object>) entry.get("location"))
                .as("the record's own declaration is where the parse read it")
                .containsEntry("uri", fixtureUri("FilmCard.java"))
                .containsEntry("line", fixtureLine("FilmCard.java", "public record FilmCard"));

            var methods = (List<Map<String, Object>>) entry.get("methods");
            assertThat(methods).extracting(m -> m.get("name"))
                .as("the classfile's own members, which is more than the source writes")
                .containsExactly("equals", "filmId", "hashCode", "title", "toString");
            assertThat(methods)
                .as("a source that declares none of them is notDeclared, never notIndexed")
                .allSatisfy(m -> assertThat(m)
                    .containsEntry("locationStatus", "notDeclared")
                    .doesNotContainKey("location"));
        }
    }

    /**
     * A class the census reaches and no walked source root does, which is every class a consumer gets
     * from a dependency jar. Reported {@code notIndexed}, and kept apart from the record case above:
     * one says the source cadence has not covered this file, the other that the file does not declare
     * the member. The shipped wire had one word for both.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeReportsAClassWithNoWalkedSourceAsNotIndexed(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "service", REMOTE_LOOKUP);

            assertThat(entry).containsEntry("locationStatus", "notIndexed")
                .doesNotContainKey("location")
                .doesNotContainKey("description");
            var methods = (List<Map<String, Object>>) entry.get("methods");
            assertThat(methods).singleElement().satisfies(m -> assertThat(m)
                .containsEntry("methodRef", REMOTE_LOOKUP + "#lookup/0")
                .containsEntry("locationStatus", "notIndexed"));
        }
    }

    /**
     * Two declarations of one name at one arity: the count is the answer the declaration family
     * defines, so both overloads report {@code ambiguous} rather than one of them winning the slot.
     * Arity is the only ground the two families share, a parse reading parameter types as written where
     * the classfile carries erased ones, so there is nothing narrower to match on.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeReportsASameArityOverloadPairAsAmbiguousRatherThanPickingOne(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "service", FILM_SERVICE);
            var methods = (List<Map<String, Object>>) entry.get("methods");

            assertThat(methods).filteredOn(m -> "describe".equals(m.get("name")))
                .hasSize(2)
                .allSatisfy(m -> assertThat(m)
                    .containsEntry("locationStatus", "ambiguous")
                    .doesNotContainKey("location"));
            assertThat(methods).filteredOn(m -> "describe".equals(m.get("name")))
                .as("the census still tells the overloads apart, the descriptor keying them")
                .extracting(m -> m.get("returnType")).containsOnly("String");
        }
    }

    /**
     * One call answers for a class that is more than one kind, where two tools each answered half. The
     * record is a service too, its accessors being public methods, which is the classpath's own answer
     * rather than a guess at what the schema wires to.
     */
    @Test
    @SuppressWarnings("unchecked")
    void codeAnswersOnceForAClassThatIsBothAServiceAndARecord(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var asService = onlyClass(fixture, "service", FILM_CARD);
            var asRecord = onlyClass(fixture, "record", FILM_CARD);

            assertThat((List<Map<String, Object>>) asService.get("components"))
                .as("the service kind carries the components too, so neither half is missing")
                .isEqualTo(asRecord.get("components"));
            assertThat((List<Map<String, Object>>) asService.get("methods"))
                .isEqualTo(asRecord.get("methods"));
        }
    }

    /** A kind the census has no population for is an argument error naming what it accepts. */
    @Test
    void codeRefusesAnAbsentOrUnknownKind(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var missing = GraphitronMcpServer.codeResult(fixture.handle(), fixture.reader(), Map.of());
            assertThat(missing.isError()).isTrue();
            assertThat(firstLine(missing)).startsWith("code:").contains("service, condition, record");

            var unknown = GraphitronMcpServer.codeResult(
                fixture.handle(), fixture.reader(), Map.of("kind", "conditions"));
            assertThat(unknown.isError()).isTrue();
        }
    }

    /** A store-less server refuses rather than answering an empty classpath, as the catalog tools do. */
    @Test
    void codeRefusesWithoutAStoreToRead() {
        var result = GraphitronMcpServer.codeResult(null, null, Map.of("kind", "service"));

        assertThat(result.isError()).isTrue();
        assertThat(firstLine(result)).startsWith("code:").contains("holds no fact store handle");
    }

    /** Paging is keyset on the class name, so following the cursor visits the census once, in order. */
    @Test
    @SuppressWarnings("unchecked")
    void codePagesByKeysetAndVisitsEveryClassOnce(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var expected = codeClasses(fixture, Map.of("kind", "service")).stream()
                .map(c -> (String) c.get("className")).toList();
            assertThat(expected).hasSizeGreaterThan(1).isSorted();

            var walked = new java.util.ArrayList<String>();
            Optional<String> cursor = Optional.empty();
            int pages = 0;
            do {
                var args = new LinkedHashMap<String, Object>();
                args.put("kind", "service");
                args.put("limit", 1);
                cursor.ifPresent(c -> args.put("cursor", c));
                var result = GraphitronMcpServer.codeResult(fixture.handle(), fixture.reader(), args);
                assertThat(firstLine(result))
                    .as("the total is the whole census on every page, not the remainder")
                    .startsWith("code: " + expected.size() + " service class(es)");

                var page = structured(result);
                var entries = (List<Map<String, Object>>) page.get("classes");
                assertThat(entries).hasSize(1);
                walked.add((String) entries.getFirst().get("className"));
                cursor = Optional.ofNullable((String) page.get("nextCursor"));
            } while (cursor.isPresent() && ++pages < expected.size());

            assertThat(walked).containsExactlyElementsOf(expected);
        }
    }

    // ---- code fixture helpers ----

    private static final String FIXTURE_PACKAGE = "no.sikt.graphitron.mcp.fixtures.";
    private static final String FILM_SERVICE = FIXTURE_PACKAGE + "code.FilmService";
    private static final String FILM_CARD = FIXTURE_PACKAGE + "code.FilmCard";
    private static final String REMOTE_LOOKUP = FIXTURE_PACKAGE + "library.RemoteLookup";

    /**
     * The one entry the {@code code} tool returns for {@code className} under {@code kind}, reached
     * through the name filter rather than by position, so a fixture class added later cannot shift a
     * case onto a different entry.
     */
    private static Map<String, Object> onlyClass(StoreFixture fixture, String kind, String className) {
        var classes = codeClasses(fixture, Map.of("kind", kind, "name", className));
        assertThat(classes).as("%s under kind=%s", className, kind).hasSize(1);
        return classes.getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> codeClasses(StoreFixture fixture, Map<String, Object> args) {
        var structured = structured(
            GraphitronMcpServer.codeResult(fixture.handle(), fixture.reader(), args));
        return (List<Map<String, Object>>) structured.get("classes");
    }

    /** The {@code file:} URI of one walked fixture source, as the declaration family spells it. */
    private static String fixtureUri(String fileName) {
        return StoreFixture.codeFixtureSources().resolve(fileName).toUri().toString();
    }

    /**
     * The 1-based line the fixture source writes {@code declaration} on, read out of the file rather
     * than pinned as a number. That keeps the assertion exact about the one thing that can go wrong
     * here, an off-by-one against the parse's own 1-based convention, without breaking every time the
     * fixture file is edited.
     */
    private static int fixtureLine(String fileName, String declaration) {
        Path file = StoreFixture.codeFixtureSources().resolve(fileName);
        try {
            var lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(declaration)) return i + 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new AssertionError("no line of " + file + " declares " + declaration);
    }

    /** One method's entry by name, for the cases whose subject is a single method of a class. */
    private static Map<String, Object> methodNamed(List<Map<String, Object>> methods, String name) {
        return methods.stream().filter(m -> name.equals(m.get("name"))).findFirst()
            .orElseThrow(() -> new AssertionError("no method named " + name));
    }

    // ---- schema ----

    /** The package the {@code schema} fixture classes live under; the census reaches all of them. */
    private static final String SCHEMA_FIXTURES = "no.sikt.graphitron.mcp.fixtures.schema.";

    /**
     * The {@code schema} fixture, chosen so one capture reaches every question the entry answers.
     *
     * <p>{@code Film} binds a table and carries a column-matched field, a {@code @reference} hop and a
     * {@code @service}; the extension site is what makes its declaration list plural. {@code FilmSummary}
     * is backed by nothing the author wrote, only by what the producer returns, which is the closure arm.
     * {@code Contested} binds a table <em>and</em> is returned by a producer, so the two backing
     * populations disagree about it. {@code FilmFilter} carries the {@code @condition} whose method the
     * store's producer view does not reach. {@code Named} and {@code Searchable} are the two SDL
     * mechanisms for an abstract type's participants.
     */
    private static final String SCHEMA_SDL = """
        type Film @table(name: "film") {
          title: String
          language: Language @reference(path: [{key: "film_language_id_fkey"}])
          summary: FilmSummary @service(service: {className: "%1$sCardService", method: "summary"})
          description: String @service(service: {className: "%1$sCardService", method: "describe"})
        }
        extend type Film {
          rating: String
        }
        interface Named { name: String }
        type Language implements Named @table(name: "language") { name: String }
        type FilmSummary { title: String released: Boolean }
        type Contested @table(name: "actor") { title: String }
        input FilmFilter @table(name: "film") {
          title: String @condition(condition: {className: "%1$sFilmConditions", method: "titled"})
        }
        union Searchable = Film | Language
        type Query {
          films(filter: FilmFilter): [Film!]!
          contested: Contested @service(service: {className: "%1$sCardService", method: "contested"})
          search: [Searchable!]!
          named: [Named!]!
        }
        """.formatted(SCHEMA_FIXTURES);

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsATableBoundTypesClaimBindingRecordClassAndColumnMatchedField(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var film = onlyType(fixture, "Film");
            assertThat(film).containsEntry("kind", "OBJECT");

            var claims = (List<Map<String, Object>>) film.get("claims");
            assertThat(claims).singleElement().satisfies(claim -> assertThat(claim)
                .containsEntry("classifier", "TABLE")
                .containsEntry("trigger", "@table")
                .containsEntry("decoded", true));
            assertThat((Map<String, Object>) film.get("demand"))
                .containsEntry("verdict", "DEMANDED").containsEntry("rule", "TABLE_TYPE");

            // The binding carries the table's full key and the arity of the reference that reached it.
            assertThat((List<Map<String, Object>>) film.get("tables"))
                .containsExactly(Map.of("table", "public.film", "candidates", 1));

            // The @table arm of the coalescing backing view: the table's own generated record. Its
            // members are empty because the classpath census deliberately never scans generated jOOQ
            // records, which is the silence that view's comment names rather than a missing read.
            assertThat((List<Map<String, Object>>) film.get("backing")).singleElement()
                .satisfies(backing -> {
                    assertThat(backing).containsEntry("declaredVia", "BOUND_TABLE");
                    assertThat((String) backing.get("class")).endsWith(".FilmRecord");
                    assertThat(backing).doesNotContainKey("members");
                });

            var title = fieldNamed(film, "Film.title");
            assertThat((List<Map<String, Object>>) title.get("claims")).singleElement()
                .satisfies(claim -> assertThat(claim)
                    .containsEntry("classifier", "TABLE_COLUMN")
                    .containsEntry("tier", "INFERRED")
                    // An inferred claim has no directive, so neither slot is emitted as null.
                    .doesNotContainKey("trigger").doesNotContainKey("decoded"));
            assertThat((Map<String, Object>) title.get("column"))
                .containsEntry("table", "public.film")
                .containsEntry("column", "title")
                .containsEntry("matchedName", "title")
                .containsEntry("matchedBy", "JOOQ_NAME");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsEveryDeclarationSiteOfAnExtendedType(@TempDir Path tmp) {
        // The delta the declaration relation buys: the retired projection reduced a type's sites to the
        // one canonical location, where an author of an extended type needs every file the shape comes
        // from. A type declared once answers identically, so the extension is where it is visible.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var declarations = (List<Map<String, Object>>) onlyType(fixture, "Film").get("declarations");
            assertThat(declarations).hasSize(2);
            assertThat(declarations).extracting(d -> d.get("isExtension"))
                .containsExactly(false, true);
            assertThat(declarations).allSatisfy(declaration -> assertThat(declaration)
                .containsEntry("kind", "OBJECT")
                .containsKeys("uri", "line", "column"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAServiceBackedFieldsMethodRefCarryingItsArity(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var summary = fieldNamed(onlyType(fixture, "Film"), "Film.summary");

            assertThat((List<Map<String, Object>>) summary.get("claims")).singleElement()
                .satisfies(claim -> assertThat(claim)
                    .containsEntry("classifier", "SERVICE")
                    .containsEntry("tier", "AUTHORED")
                    .containsEntry("trigger", "@service")
                    .containsEntry("decoded", true)
                    .containsKey("location"));
            assertThat((List<Map<String, Object>>) summary.get("methods")).containsExactly(Map.of(
                "methodRef", SCHEMA_FIXTURES + "CardService#summary/1",
                "declaredVia", "SERVICE",
                "candidates", 1));
            assertThat(summary).doesNotContainKey("column");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaMasksTheColumnBindingOfAFieldAnAuthoredDirectiveClaims(@TempDir Path tmp) {
        // Film.description is named after a column of film, so the structural classifier reads it as a
        // table column and its own relation keeps that row on purpose, which is what lets a diagnostic
        // say "would classify as a table column; @service overrides it". The resolution is where the
        // store says which reading won, and reporting a column binding here would tell an agent the
        // field's value comes from a column when it comes from a method.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var description = fieldNamed(onlyType(fixture, "Film"), "Film.description");

            assertThat((List<Map<String, Object>>) description.get("claims")).singleElement()
                .satisfies(claim -> assertThat(claim)
                    .containsEntry("classifier", "SERVICE")
                    .containsEntry("tier", "AUTHORED"));
            assertThat(description).doesNotContainKey("column");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAConditionsMethodTheStoresProducerViewDoesNotCarry(@TempDir Path tmp) {
        // The second method population. The producer view is scoped to @service and @externalField, so
        // reading only it would leave this coordinate's method slot empty with nothing on the wire to
        // say a slot had been dropped rather than found absent.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var title = fieldNamed(onlyType(fixture, "FilmFilter"), "FilmFilter.title");

            assertThat((List<Map<String, Object>>) title.get("methods")).containsExactly(Map.of(
                "methodRef", SCHEMA_FIXTURES + "FilmConditions#titled/1",
                "declaredVia", "CONDITION",
                "candidates", 1));
            // @condition claims no classification, so the structural reading still wins the coordinate
            // and the column binding stands beside the method rather than being masked by it.
            assertThat((List<Map<String, Object>>) title.get("claims")).singleElement()
                .satisfies(claim -> assertThat(claim).containsEntry("classifier", "TABLE_COLUMN"));
            assertThat((Map<String, Object>) title.get("column"))
                .containsEntry("table", "public.film").containsEntry("column", "title");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAReferencingFieldsHopWithBothEndpointsQualified(@TempDir Path tmp) {
        // The delta over the retired projection, which held a bare target table name and a key name:
        // both endpoints come back schema-qualified, and the two arities say how certain the hop is.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var language = fieldNamed(onlyType(fixture, "Film"), "Film.language");

            assertThat((List<Map<String, Object>>) language.get("joinPath")).containsExactly(Map.of(
                "ordinal", 0, "position", 0,
                "via", "KEY", "keyMatchedBy", "SQL_NAME",
                "fromTable", "public.film", "toTable", "public.language",
                "constraint", "film_language_id_fkey", "fkOnFrom", true,
                "targets", 1, "candidates", 1));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAClosureBackedTypesClassAndTheMemberNamesItOffers(@TempDir Path tmp) {
        // The closure's own reachability is derived and tested on the store side; what this asserts is
        // the rendering, which is the class, its provenance and the slots the class offers an author.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var summary = onlyType(fixture, "FilmSummary");

            assertThat((List<Map<String, Object>>) summary.get("backing")).singleElement()
                .satisfies(backing -> {
                    assertThat(backing)
                        .containsEntry("class", SCHEMA_FIXTURES + "FilmSummary")
                        .containsEntry("declaredVia", "BACKING_CLOSURE");
                    // Both accessor prefixes the bean rule accepts, each with the slot name the rule
                    // derives and the declaration it resolves back to.
                    assertThat((List<Map<String, Object>>) backing.get("members")).containsExactly(
                        Map.of("name", "released", "type", "boolean",
                            "origin", "BEAN_ACCESSOR", "accessorMethodName", "isReleased"),
                        Map.of("name", "title", "type", "String",
                            "origin", "BEAN_ACCESSOR", "accessorMethodName", "getTitle"));
                });
            // Nothing the author wrote claims or binds this type; the producer is what reaches it.
            assertThat(summary).doesNotContainKeys("claims", "tables");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsBothBackingsOfAContestedTypeRatherThanApplyingThePrecedence(@TempDir Path tmp) {
        // The walk resolves this pair by reading the table and never consulting the class. That is a
        // defensible reading and a consumer may still apply it by filtering on declaredVia; what the
        // tool may not do is report the precedence as agreement, so both rows cross the wire.
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var contested = onlyType(fixture, "Contested");

            var backing = (List<Map<String, Object>>) contested.get("backing");
            assertThat(backing).hasSize(2);
            assertThat(backing).extracting(b -> b.get("declaredVia"))
                .containsExactlyInAnyOrder("BOUND_TABLE", "BACKING_CLOSURE");
            assertThat(backing).extracting(b -> b.get("class"))
                .contains(SCHEMA_FIXTURES + "FilmSummary");

            assertThat((Map<String, Object>) contested.get("backingConflict"))
                .containsEntry("candidates", 2);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAnAbstractTypesParticipantsUnderTheSdlMechanismThatDeclaresThem(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var named = onlyType(fixture, "Named");
            assertThat(named).containsEntry("kind", "INTERFACE")
                .containsEntry("implementors", List.of("Language"))
                .doesNotContainKey("unionMembers");

            var searchable = onlyType(fixture, "Searchable");
            assertThat(searchable).containsEntry("kind", "UNION")
                .containsEntry("unionMembers", List.of("Film", "Language"))
                .doesNotContainKey("implementors");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAnExemptCoordinateWithTheRuleThatPutsItOutOfScope(@TempDir Path tmp) {
        // The answer with no predecessor at all. The retired wire reported one verdict whether a
        // coordinate failed to classify or was never asked to, so an agent could not tell "graphitron
        // could not read this" from "graphitron does not classify this kind of coordinate".
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            assertThat((Map<String, Object>) fieldNamed(onlyType(fixture, "Named"), "Named.name")
                .get("demand"))
                .containsEntry("verdict", "EXEMPT").containsEntry("rule", "INTERFACE_TYPE");

            assertThat((Map<String, Object>) fieldNamed(onlyType(fixture, "FilmFilter"),
                "FilmFilter.title").get("demand"))
                .containsEntry("verdict", "EXEMPT").containsEntry("rule", "INPUT_TYPE");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaNarrowsToOneTypeAndReportsAnUndeclaredNameAsNotFound(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var found = schemaResult(fixture, Map.of("type", "Film"));
            assertThat((List<Map<String, Object>>) structured(found).get("types")).hasSize(1);
            assertThat(firstLine(found)).contains("type 'Film'");

            var missing = schemaResult(fixture, Map.of("type", "Nonexistent"));
            assertThat(structured(missing))
                .containsEntry("types", List.of())
                .containsEntry("notFound", "Nonexistent");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaPagesByKeysetAndVisitsEveryTypeOnce(@TempDir Path tmp) {
        try (var fixture = StoreFixture.ofSchema(tmp, SCHEMA_SDL)) {
            var whole = schemaTypeRefs(fixture, Map.of("limit", 1_000));
            assertThat(whole).doesNotHaveDuplicates().isSorted().hasSizeGreaterThan(3);

            var walked = new ArrayList<String>();
            Optional<String> cursor = Optional.empty();
            for (int page = 0; page <= whole.size(); page++) {
                var args = new LinkedHashMap<String, Object>();
                args.put("limit", 2);
                cursor.ifPresent(c -> args.put("cursor", c));
                var result = structured(schemaResult(fixture, args));
                walked.addAll(typeRefs(result));
                // The unpaged total rides every page, so an agent never counts the pages to learn it.
                assertThat(firstLine(schemaResult(fixture, args)))
                    .startsWith("schema: " + whole.size() + " type(s)");
                cursor = Optional.ofNullable((String) result.get("nextCursor"));
                if (cursor.isEmpty()) break;
            }
            assertThat(walked).as("keyset paging visits every type exactly once, in order")
                .isEqualTo(whole);
        }
    }

    @Test
    void schemaRefusesWithoutAStoreToRead() {
        // An empty answer would read as a schema declaring no types at all, which is the reading the
        // catalog and diagnostics tools refuse for the same reason.
        var result = SchemaView.schemaResult(LspSchemaSnapshot.unavailable(), null, null, Map.of());
        assertThat(result.isError()).isTrue();
        assertThat(firstLine(result)).contains("schema").contains("holds no fact store handle");
    }

    @Test
    @SuppressWarnings("unchecked")
    void schemaReportsAConflictedCoordinatesDirectivesAndMessage(@TempDir Path tmp) throws Exception {
        // The one case that needs a build rather than a capture: the conflict view's domain gate joins
        // walk_claim_domain_field, which is written by the detection pass over the walk's own reach.
        try (var build = StoreBackedBuild.run(tmp, "conflicted", CONFLICTED_SDL);
             var server = server(build);
             var client = connect(server.port())) {
            client.initialize();

            var mutation = onlyType(structured(client.callTool(
                McpSchema.CallToolRequest.builder("schema")
                    .arguments(Map.of("type", "Mutation")).build())), "Mutation");
            var conflict = (Map<String, Object>) fieldNamed(mutation, "Mutation.deleteFilm")
                .get("conflict");

            assertThat(conflict)
                .containsEntry("verdict", "CONFLICT")
                // The canonical comma-joined render the store groups by, sorted, so two readers
                // grouping on a directive set cannot split a group on claim order.
                .containsEntry("directives", "mutation,service")
                .containsEntry("message",
                    "Field 'Mutation.deleteFilm': @service, @mutation are mutually exclusive")
                .containsKey("location");

            // Both rival claims survive with their own provenance, which is what the conflicted arm
            // of the retired wire carried and what generalises here to every coordinate.
            assertThat((List<Map<String, Object>>) fieldNamed(mutation, "Mutation.deleteFilm")
                .get("claims"))
                .extracting(claim -> claim.get("classifier"))
                .containsExactly("MUTATION", "SERVICE");

            // The relation carries both grains and marks the type grain by a null field name, so a
            // field's violation must not surface as its parent type's.
            assertThat(mutation).doesNotContainKey("conflict");
        }
    }

    /** A coordinate two claiming directives contest, which is what the conflict relation reports on. */
    private static final String CONFLICTED_SDL = """
        type Film @table(name: "film") {
          title: String
        }
        type Query { film: Film }
        type Mutation {
          deleteFilm(id: ID!): Boolean
            @mutation(typeName: DELETE)
            @service(service: {className: "com.example.FilmService", method: "delete"})
        }
        """;

    // ---- schema helpers ----

    private static McpSchema.CallToolResult schemaResult(
        StoreFixture fixture, Map<String, Object> args
    ) {
        return SchemaView.schemaResult(LspSchemaSnapshot.unavailable(), fixture.handle(),
            fixture.reader(), args);
    }

    /**
     * The one entry the tool returns for {@code typeName}, reached through the narrow rather than by
     * position, so a fixture type added later cannot shift a case onto a different entry.
     */
    private static Map<String, Object> onlyType(StoreFixture fixture, String typeName) {
        return onlyType(structured(schemaResult(fixture, Map.of("type", typeName))), typeName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyType(Map<String, Object> structured, String typeName) {
        var types = (List<Map<String, Object>>) structured.get("types");
        assertThat(types).as("the narrow to %s returns exactly that type", typeName).hasSize(1);
        assertThat(types.getFirst()).containsEntry("typeRef", typeName);
        return types.getFirst();
    }

    /** One field entry of a type by its coordinate id, which is the id every tool spells it as. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldNamed(Map<String, Object> type, String fieldRef) {
        return ((List<Map<String, Object>>) type.get("fields")).stream()
            .filter(field -> fieldRef.equals(field.get("fieldRef")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no field entry for " + fieldRef));
    }

    private static List<String> schemaTypeRefs(StoreFixture fixture, Map<String, Object> args) {
        return typeRefs(structured(schemaResult(fixture, args)));
    }

    @SuppressWarnings("unchecked")
    private static List<String> typeRefs(Map<String, Object> structured) {
        return ((List<Map<String, Object>>) structured.get("types")).stream()
            .map(type -> (String) type.get("typeRef"))
            .toList();
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

    /**
     * A server holding the fixture's live workspace, its session store handle and its reader, as the
     * dev loop wires it: the host mints both, so a case that passed only one would be testing a
     * server no session builds.
     */
    private static GraphitronMcpServer server(StoreBackedBuild build) throws IOException {
        return new GraphitronMcpServer(loopback(0), build.workspace, null, null, null, null,
            build.handle(), build.reader());
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
    void methodRefIdsCarryTheArityTheDeclarationFamilyIsMatchedOn(@TempDir Path tmp) {
        // The methodRef a tool emits is exactly fqcn#method/arity over the triple the java_ family is
        // matched on, arity being the only ground it and the census share. Pinned against the tool's own
        // parameter list rather than a second spelling of the grammar, so the id and the arity it
        // promises cannot drift apart: an id claiming an arity the entry does not carry is the failure.
        try (var fixture = StoreFixture.ofCodeFixtures(tmp)) {
            var entry = onlyClass(fixture, "service", FILM_SERVICE);

            assertThat((List<Map<String, Object>>) entry.get("methods")).allSatisfy(m -> {
                int arity = ((List<?>) m.get("parameters")).size();
                assertThat(m).containsEntry("methodRef",
                    FILM_SERVICE + "#" + m.get("name") + "/" + arity);
            });
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

    /**
     * Discovery hands off to description, and both ends now read the same census: the corpus is
     * composed from it rather than from a projection, so an id search emits is an id describe accepts
     * because they are drawn from one relation rather than because two shapes agree.
     */
    @Test
    @SuppressWarnings("unchecked")
    void catalogSearchReturnsRankedTableIdsWhoseTopFeedsCatalogDescribe(@TempDir Path tmp) throws Exception {
        // The shared embedder warm carries a fake (no ONNX); BM25 over the descriptors carries the
        // ranking. The server kicks the index warm at bind; awaitRagWarm() waits it out deterministically.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> new FakeEmbedder(384)));
        try (var census = StoreFixture.ofCatalog(tmp);
             var server = new GraphitronMcpServer(
                loopback(0), new Workspace(), embedderWarm, null, RagConfig.temporary(),
                null, census.handle(), census.reader());
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

            // The top id feeds straight into catalog.describe, which resolves it against the census.
            var describe = structured(client.callTool(McpSchema.CallToolRequest.builder("catalog.describe")
                .arguments(Map.of("table", topId)).build()));
            assertThat(describe).containsEntry("resolution", "resolved")
                .containsEntry("name", top.get("name"));
        }
    }

    @Test
    void catalogSearchReportsWarmingWhileTheIndexIsStillBuilding(@TempDir Path tmp) throws Exception {
        // A blocking embedder pins the index in Warming; the first search reports the degradation. The
        // store is present, which is what separates this arm from the refusal below: the corpus reads,
        // and what is missing is the embedding of it.
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> new BlockingEmbedder(384)));
        try (var census = StoreFixture.ofCatalog(tmp);
             var server = new GraphitronMcpServer(
                loopback(0), new Workspace(), embedderWarm, null, RagConfig.temporary(),
                null, census.handle(), census.reader());
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

    /**
     * A corpus that cannot be composed is not an index that is still building, so the store-less server
     * refuses where it used to report warming. Telling an agent to retry would point it at a wiring
     * fact no retry can change, and the search corpus is the census like every other catalog answer.
     */
    @Test
    void catalogSearchRefusesWithoutAStoreToRead() throws Exception {
        var embedderWarm = startedAwaited(new AsyncWarm<Embedder>("e", () -> new FakeEmbedder(384)));
        try (var server = new GraphitronMcpServer(
                loopback(0), new Workspace(), embedderWarm, null, RagConfig.temporary());
             var client = connect(server.port())) {
            client.initialize();

            var result = client.callTool(McpSchema.CallToolRequest.builder("catalog.search")
                .arguments(Map.of("query", "customer address")).build());
            assertThat(result.isError()).isTrue();
            assertThat(firstLine(result))
                .contains("catalog.search").contains("holds no fact store handle");
        }
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
