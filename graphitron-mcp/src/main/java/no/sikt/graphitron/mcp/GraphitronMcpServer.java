package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.mcp.rag.AsyncWarm;
import no.sikt.graphitron.mcp.rag.CatalogSearchIndex;
import no.sikt.graphitron.mcp.rag.Embedder;
import no.sikt.graphitron.mcp.rag.EmbeddingStore;
import no.sikt.graphitron.mcp.rag.RagConfig;
import no.sikt.graphitron.mcp.rag.WarmState;
import no.sikt.graphitron.mcp.rag.docs.DocsIndex;
import no.sikt.graphitron.model.boot.StoreReader;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The MCP server embedded in the {@code graphitron:dev} JVM: hosts the MCP Java SDK's
 * servlet-based Streamable HTTP transport in an embedded Jetty {@link Server} bound on a
 * loopback address. It serves the ambient {@code instructions} string returned in the MCP
 * {@code initialize} handshake, a single argument-less {@code about} prompt, and tools and
 * resources that read the live generator model.
 *
 * <p>Mirrors the sibling {@code DevServer}'s transport-glue shape: one instance per Mojo
 * invocation, {@link AutoCloseable}, constructed with an {@link InetSocketAddress} so production
 * passes {@code 127.0.0.1:8488} and tests bind an ephemeral port. The bundled prose is read once
 * at startup from jar resources under {@code /mcp/}; it is shape, not state. The ambient
 * instructions are composed from two of those resources so the {@code execute} routing sentence
 * tracks that tool's conditional registration; the composition is fixed for the server's lifetime.
 *
 * <p>The server holds the same live {@link Workspace} handle the LSP {@code DevServer}
 * holds: the one instance {@code DevMojo} builds and the schema / classpath / source watchers
 * mutate in place on every save and recompile. Tools read off that shared reference on every
 * call, so each call observes the latest build state without any new trigger or refresh path.
 *
 * <p>The MCP spec serves the Streamable HTTP transport over a single endpoint. The provider
 * matches an incoming request by {@code getRequestURI().equals("/mcp")}, so the servlet is
 * mounted at exactly {@value #MCP_ENDPOINT} under the root context. The transport drives SSE
 * response streams via {@code request.startAsync()}, so the servlet holder must declare async
 * support or the first stream request fails at runtime.
 *
 * <p>Cancellation: {@link #close()} closes the MCP server (which closes the transport) and
 * stops Jetty. A failed bind surfaces from the constructor as an {@link IOException} (Jetty
 * wraps the underlying {@code BindException}); callers translate it into a Mojo error naming
 * the conflict, exactly as {@code DevServer} does for the LSP socket.
 */
public final class GraphitronMcpServer implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphitronMcpServer.class);

    /** Single MCP endpoint path. Must agree with the committed {@code .mcp.json} URL and the dev-loop docs. */
    static final String MCP_ENDPOINT = "/mcp";

    /** Server key advertised in the handshake; matches the client-config server name {@code graphitron}. */
    static final String SERVER_NAME = "graphitron";

    private final McpSyncServer mcpServer;
    private final Server httpServer;
    private final ServerConnector connector;
    private final AtomicBoolean closed = new AtomicBoolean();

    // The docs.search handler, holding the shared embedder warm and the docs-index warm. The
    // warms may be absent (the structured-tool tests / an IDE run off un-embedded classes); the tool
    // is then always advertised but reads as still-warming and degrades to the structured tools.
    private final DocsSearchTool docsSearchTool;

    // The semantic catalog index behind catalog.search: self-observes the census through the store
    // reader, persists its Lucene index under the RagConfig cache dir, and rides the same shared
    // embedder warm docs.search uses. Null when the server is stood up without RAG or without a
    // store; the tool tells those two apart, degrading for the first and refusing for the second.
    private final CatalogSearchIndex catalogSearchIndex;

    /**
     * Structured-only server (no RAG): the docs-index and embedder warms and the catalog cache are
     * absent, so {@code docs.search} and {@code catalog.search} are advertised but degrade to the
     * structured tools. The entry point the structured-tool tests use; production uses the full
     * constructor.
     */
    public GraphitronMcpServer(InetSocketAddress address, Workspace workspace) throws IOException {
        this(address, workspace, null, null, null);
    }

    /**
     * RAG server without a catalog cache: docs.search rides its warms, catalog.search degrades.
     * For callers that wire only the docs search; production uses the full form.
     */
    public GraphitronMcpServer(
        InetSocketAddress address, Workspace workspace,
        AsyncWarm<Embedder> embedderWarm, AsyncWarm<DocsIndex> docsWarm
    ) throws IOException {
        this(address, workspace, embedderWarm, docsWarm, null);
    }

    /**
     * Builds and starts the server on the supplied loopback address, holding the live
     * {@code workspace} the tools read off, the two RAG warms {@code docs.search} rides, and the
     * {@code ragConfig} the catalog index persists under. A taken port surfaces as an
     * {@link IOException}; the caller translates it into a Mojo error. On any startup failure the
     * partially-built server is torn down before the exception propagates. The embedder / docs
     * warms are read-only here (their lifecycle is owned by the caller); the catalog index warm is
     * owned by the {@link CatalogSearchIndex} this server holds, so the server kicks it after bind.
     * A RAG warm failure leaves the server structured-only and never blocks the bind.
     */
    public GraphitronMcpServer(
        InetSocketAddress address, Workspace workspace,
        AsyncWarm<Embedder> embedderWarm, AsyncWarm<DocsIndex> docsWarm, RagConfig ragConfig
    ) throws IOException {
        this(address, workspace, embedderWarm, docsWarm, ragConfig, null);
    }

    /**
     * The six-arg form without a fact store: the store-backed tools are advertised but refuse when
     * called, naming what is missing. The entry point of the store-less test boots; production uses
     * the full constructor below.
     */
    public GraphitronMcpServer(
        InetSocketAddress address, Workspace workspace,
        AsyncWarm<Embedder> embedderWarm, AsyncWarm<DocsIndex> docsWarm, RagConfig ragConfig,
        ExecuteTool.Config executeConfig
    ) throws IOException {
        this(address, workspace, embedderWarm, docsWarm, ragConfig, executeConfig, null, null);
    }

    /**
     * The full production form: the five-arg server plus the {@code execute} tool
     * configuration and the fact store handle. When {@code executeConfig} is {@code null} (no
     * dev database configured), the {@code execute} tool is not registered at all, the stronger
     * form of the degrade-gracefully posture: the RAG tools stay advertised and degrade, the
     * execute tool is simply absent, and every other tool keeps working with no database. A
     * {@code null} {@code storeHandle} takes the other posture: the diagnostics tools stay
     * advertised and refuse per call, because an empty answer from a missing store would read
     * as a clean schema.
     *
     * <p>The handle carries the session's one live {@code DSLContext} and the graph whose partition
     * this server reads, and the caller owns it: {@code DevMojo} opens the store once and closes it
     * at cleanup. This server never opens the persisted file itself, which could silently be a
     * different store than the one the session writes. Sharing the writer's connection is safe for a
     * tool whose answer is one query, this server being turn-based, and stops being safe for one
     * whose answer is several: a nested transaction on the writer's connection is a savepoint rather
     * than a boundary. So the caller mints a {@link StoreReader} too, and the tools that assemble an
     * answer from several relations read through that instead. The reader is the caller's to close,
     * for the same reason the handle is.
     */
    public GraphitronMcpServer(
        InetSocketAddress address, Workspace workspace,
        AsyncWarm<Embedder> embedderWarm, AsyncWarm<DocsIndex> docsWarm, RagConfig ragConfig,
        ExecuteTool.Config executeConfig, StoreHandle storeHandle, StoreReader storeReader
    ) throws IOException {
        this.docsSearchTool = new DocsSearchTool(embedderWarm, docsWarm);
        // The index reads the census itself, through the reader the host minted: the store is where a
        // capture lands, so reading it per observation is what makes the ranking current. The reader
        // rather than the handle because the corpus is two queries, and the graph name off the handle,
        // so the index exists only once both have arrived.
        this.catalogSearchIndex = (embedderWarm != null && ragConfig != null
            && storeHandle != null && storeReader != null)
            ? new CatalogSearchIndex(storeReader, storeHandle.graphName(), embedderWarm, ragConfig)
            : null;

        // The ambient instructions string is composed rather than one fixed resource, mirroring the
        // conditional tool registration below: the execute tool exists exactly when a dev database is
        // configured, so its routing sentence has to appear exactly then. A static line would advertise
        // an absent tool to every project without a database; omitting it would leave the tool the
        // instructions never route to. ServerInstructionsTest pins the agreement per boot.
        String instructions = executeConfig == null
            ? loadResource("/mcp/instructions.txt")
            : loadResource("/mcp/instructions.txt").stripTrailing()
                + "\n\n" + loadResource("/mcp/instructions-execute.txt");
        String aboutText = loadResource("/mcp/about.md");

        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
            .mcpEndpoint(MCP_ENDPOINT)
            .build();

        // The bundled directive grammar projected once off the frozen vocabulary registry
        // (shape, not state). The directives resource unions this with the live snapshot's
        // user-declared directives on every read; the bundled half never changes, so it is computed
        // here rather than per read.
        List<DirectiveShape> bundledDirectives =
            CatalogBuilder.buildSnapshot(workspace.vocabulary().registry()).directives();

        // Build the sync server before mounting the servlet: this wires the session factory into
        // the transport provider, so it is ready before Jetty accepts the first request. The
        // tools(false) / resources(false, false) booleans are the listChanged (and, for resources,
        // subscribe) capabilities; the tool and resource list is fixed for the server's lifetime
        // (the one conditional entry, the execute tool, is present exactly when a dev database is
        // configured), so they stay false even though the tools and resource read live state.
        var tools = new java.util.ArrayList<>(List.of(
            statusTool(workspace),
            catalogTablesTool(storeHandle), catalogDescribeTool(storeHandle, storeReader),
            servicesTool(workspace), conditionsTool(workspace), recordsTool(workspace),
            schemaTool(workspace, storeHandle), diagnosticsTool(workspace, storeHandle),
            diagnosticsAggregateTool(workspace, storeHandle),
            docsSearchTool.specification(), catalogSearchTool(storeHandle, storeReader)));
        if (executeConfig != null) {
            tools.add(new ExecuteTool(executeConfig).specification());
        }
        this.mcpServer = McpServer.sync(transportProvider)
            .serverInfo(SERVER_NAME, version())
            .instructions(instructions)
            .capabilities(McpSchema.ServerCapabilities.builder()
                .prompts(false).tools(false).resources(false, false).build())
            .prompts(aboutPrompt(aboutText))
            .tools(tools)
            .resources(directivesResource(workspace, bundledDirectives))
            .build();

        this.httpServer = new Server();
        this.connector = new ServerConnector(httpServer);
        connector.setHost(address.getHostString());
        connector.setPort(address.getPort());
        httpServer.addConnector(connector);

        var context = new ServletContextHandler();
        context.setContextPath("/");
        var holder = new ServletHolder(transportProvider);
        holder.setAsyncSupported(true);
        context.addServlet(holder, MCP_ENDPOINT);
        httpServer.setHandler(context);

        try {
            // Open the connector explicitly so a taken port fails fast as an IOException here,
            // rather than as a wrapped lifecycle failure out of Server.start().
            connector.open();
        } catch (IOException e) {
            stop();
            throw e;
        }
        try {
            httpServer.start();
        } catch (Exception e) {
            stop();
            throw new IOException("graphitron:dev: failed to start MCP HTTP server", e);
        }

        // Kick the initial catalog-index build off the dev thread, so an agent's first search
        // usually lands a ready index. The shared embedder warm is already started by the caller; the
        // index warm only awaits it. The warm never touches the dev loop; a failure leaves search
        // degraded, not the server.
        if (catalogSearchIndex != null) {
            catalogSearchIndex.start();
        }
    }

    /**
     * Blocks until the catalog search index reaches a terminal warm state, or returns immediately
     * when the server is structured-only. A test affordance for deterministically driving the ready
     * arm; production drives the tool and reads the degradation message while warming.
     */
    void awaitRagWarm() {
        if (catalogSearchIndex != null) {
            catalogSearchIndex.awaitWarm();
        }
    }

    /** The bound local port (the ephemeral port under tests, {@code 8488} in production). */
    public int port() {
        return connector.getLocalPort();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        stop();
    }

    private void stop() {
        if (catalogSearchIndex != null) {
            try {
                catalogSearchIndex.close();
            } catch (RuntimeException e) {
                LOGGER.warn("graphitron:dev: error closing catalog search index: {}", e.getMessage());
            }
        }
        try {
            mcpServer.closeGracefully();
        } catch (RuntimeException e) {
            LOGGER.warn("graphitron:dev: error closing MCP server: {}", e.getMessage());
        }
        try {
            httpServer.stop();
        } catch (Exception e) {
            LOGGER.warn("graphitron:dev: error stopping MCP HTTP server: {}", e.getMessage());
        }
    }

    /**
     * The single argument-less {@code about} prompt: MCP-aware clients surface it as a slash
     * command (Claude Code: {@code /mcp__graphitron__about}) returning the bundled explainer.
     */
    private static McpServerFeatures.SyncPromptSpecification aboutPrompt(String aboutText) {
        var prompt = McpSchema.Prompt.builder("about")
            .description("Explains the graphitron project and the dev loop you are connected to.")
            .build();
        // The explainer is fixed, so build the immutable result once and hand the same instance back.
        var result = McpSchema.GetPromptResult.builder(List.of(
                new McpSchema.PromptMessage(
                    McpSchema.Role.USER,
                    McpSchema.TextContent.builder(aboutText).build())))
            .description("About graphitron")
            .build();
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> result);
    }

    /**
     * The liveness {@code status} tool: takes no arguments and reads {@link Workspace#snapshot()}
     * off the live handle on every call, so the answer reflects the latest build state.
     *
     * <p>The snapshot is reported on its two orthogonal axes rather than a flattened tri-state:
     * {@code availability} ({@code Built} vs {@code Unavailable}) and {@code freshness}
     * ({@code Current} vs {@code Previous}, absent when unavailable). The two fields are mapped
     * exhaustively off the {@link LspSchemaSnapshot} sealed permits, so the MCP view never
     * re-derives a fork the model owns. Domain counts (tables, references, diagnostics) are
     * deliberately out of scope: the structured tools own those wire schemas.
     */
    private static McpServerFeatures.SyncToolSpecification statusTool(Workspace workspace) {
        // The (name, inputSchema) builder overload is the non-deprecated entry point; the explicit
        // empty object schema is the no-argument input the MCP spec requires every tool to carry.
        var tool = McpSchema.Tool.builder("status", Map.of("type", "object", "properties", Map.of()))
            .title("Dev-loop status")
            .description("Reports graphitron:dev MCP server liveness plus the live schema-snapshot "
                + "state on two axes: availability (Built / Unavailable) and, when built, freshness "
                + "(Current / Previous). Takes no arguments.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> statusResult(workspace.snapshot()))
            .build();
    }

    /**
     * Maps the live snapshot to the {@code status} tool result. Exhaustive over the
     * {@link LspSchemaSnapshot} sealed permits so a new freshness/availability arm forces a
     * compile-time choice here rather than silently flattening. {@code freshness} is omitted
     * (not null-valued) on the unavailable arm: there is no freshness axis before the first build.
     */
    private static McpSchema.CallToolResult statusResult(LspSchemaSnapshot snapshot) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("toolsReady", true);
        String summary = switch (snapshot) {
            case LspSchemaSnapshot.Unavailable ignored -> {
                fields.put("availability", "Unavailable");
                yield "graphitron:dev MCP server live; schema snapshot Unavailable "
                    + "(no successful build yet).";
            }
            case LspSchemaSnapshot.Built.Current ignored -> {
                fields.put("availability", "Built");
                fields.put("freshness", "Current");
                yield "graphitron:dev MCP server live; schema snapshot Built/Current.";
            }
            case LspSchemaSnapshot.Built.Previous ignored -> {
                fields.put("availability", "Built");
                fields.put("freshness", "Previous");
                yield "graphitron:dev MCP server live; schema snapshot Built/Previous "
                    + "(last good parse; latest edit failed to parse).";
            }
        };
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(Map.copyOf(fields))
            .build();
    }

    /** Default {@code catalog.tables} page size: well under MCP response limits, paged by cursor. */
    private static final int DEFAULT_TABLES_LIMIT = 100;

    /**
     * {@code catalog.tables}: lists the database tables the schema wires to, as a query over the
     * {@code sql_table} census scoped to the session's graph. Paging is keyset on the
     * {@code (schema, name)} ordering, with a {@code nextCursor} until the last page, so the
     * ordering the page is drawn in is the cursor rather than an offset into it.
     */
    private static McpServerFeatures.SyncToolSpecification catalogTablesTool(StoreHandle storeHandle) {
        var tool = McpSchema.Tool.builder("catalog.tables", Map.of(
                "type", "object",
                "properties", Map.of(
                    "schema", Map.of("type", "string",
                        "description", "Filter to one schema (exact, case-insensitive)."),
                    "name", Map.of("type", "string",
                        "description", "Case-insensitive substring filter on the SQL table name."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum tables per page (default " + DEFAULT_TABLES_LIMIT + ")."),
                    "cursor", Map.of("type", "string",
                        "description", "Opaque page cursor from a prior call's nextCursor."))))
            .title("List catalog tables")
            .description("Lists the database tables the GraphQL schema wires to, with optional "
                + "schema and SQL-name-substring filters, paged via an opaque cursor. Ordered by "
                + "schema then SQL name, which is also what the cursor is keyed by, so following a "
                + "cursor visits every table once. Each table "
                + "carries its schema, SQL name, and comment when jOOQ codegen captured one. SQL "
                + "names drive discovery; use catalog.describe for a single table's columns, keys, "
                + "indexes, and foreign keys.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> catalogTablesResult(storeHandle, request.arguments()))
            .build();
    }

    /**
     * A handle-less server refuses rather than answering an empty census, which would read as a
     * database with no tables. This is not the pre-capture case: a store with no {@code sql_table}
     * rows is an answer, and absence of rows is absence of tables.
     */
    static McpSchema.CallToolResult catalogTablesResult(StoreHandle store, Map<String, Object> args) {
        if (store == null) {
            return DiagnosticFacets.refusal("catalog.tables");
        }
        Optional<String> schema = McpWire.stringArg(args, "schema");
        Optional<String> name = McpWire.stringArg(args, "name");
        int limit = McpWire.intArg(args, "limit", DEFAULT_TABLES_LIMIT);
        if (limit < 1) limit = DEFAULT_TABLES_LIMIT;

        var page = CatalogQueries.tables(
            store, schema, name, McpWire.stringArg(args, "cursor"), limit);

        var tableList = new ArrayList<Map<String, Object>>(page.entries().size());
        for (var t : page.entries()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("schema", t.schema());
            entry.put("name", t.name());
            McpWire.putIfNotNull(entry, "comment", t.comment());
            tableList.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("tables", tableList);
        page.nextCursor().ifPresent(c -> fields.put("nextCursor", c));

        String summary = "catalog.tables: " + page.total() + " table(s)"
            + schema.map(s -> " in schema '" + s + "'").orElse("")
            + name.map(n -> " matching '" + n + "'").orElse("")
            + "; showing " + page.entries().size()
            + (page.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    /**
     * {@code catalog.describe}: one table's columns, keys, indexes, and foreign keys, read from the
     * store on every call. An unqualified name two schemas carry returns a structured
     * {@code ambiguous} result; an unknown name returns {@code notFound}.
     *
     * <p>Takes both the handle and the reader because it needs one thing from each: the graph whose
     * partition the answer is confined to, and a connection the answer's several queries can share one
     * transaction on. It refuses when either is absent, the reader being the thing it answers through
     * and the handle being what says which module's catalog it would have described.
     */
    private static McpServerFeatures.SyncToolSpecification catalogDescribeTool(
        StoreHandle store, StoreReader reader
    ) {
        var tool = McpSchema.Tool.builder("catalog.describe", Map.of(
                "type", "object",
                "properties", Map.of(
                    "table", Map.of("type", "string",
                        "description", "Bare or schema-qualified SQL table name (e.g. \"film\" or \"public.film\")."),
                    "schema", Map.of("type", "string",
                        "description", "Schema for the table; the alternative to inline qualification.")),
                "required", List.of("table")))
            .title("Describe a catalog table")
            .description("Describes one database table: columns (SQL and Java names, SQL types, "
                + "nullability, and comments where the database declares them) in table-definition "
                + "order, the primary key, every unique key the database declares, indexes, and "
                + "foreign keys in and out (with their column pairs). "
                + "Foreign-key endpoints name neighbouring tables by their schema-qualified SQL name. "
                + "Column comments appear only when codegen ran with comments enabled; their absence "
                + "reflects codegen configuration, not a missing database comment. An ambiguous "
                + "unqualified name returns the candidate schemas to re-call qualified.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> catalogDescribeResult(store, reader, request.arguments()))
            .build();
    }

    static McpSchema.CallToolResult catalogDescribeResult(
        StoreHandle store, StoreReader reader, Map<String, Object> args
    ) {
        if (store == null || reader == null) {
            return DiagnosticFacets.refusal("catalog.describe");
        }
        String table = McpWire.stringArg(args, "table").orElse("");
        Optional<String> schema = McpWire.stringArg(args, "schema");

        var fields = new LinkedHashMap<String, Object>();
        String summary = switch (CatalogQueries.describe(reader, store.graphName(), table, schema)) {
            case CatalogQueries.TableResolution.Resolved r -> {
                fields.put("resolution", "resolved");
                mapResolvedTable(fields, r.table());
                yield "catalog.describe: " + r.table().qualifiedName() + " ("
                    + r.table().columns().size() + " column(s)).";
            }
            case CatalogQueries.TableResolution.Ambiguous a -> {
                fields.put("resolution", "ambiguous");
                fields.put("schemas", a.schemas());
                yield "catalog.describe: table '" + table + "' is ambiguous, carried by schemas "
                    + a.schemas() + "; re-call qualified (e.g. \"" + a.schemas().get(0) + "." + table + "\").";
            }
            case CatalogQueries.TableResolution.NotFound ignored -> {
                fields.put("resolution", "notFound");
                fields.put("table", table);
                yield "catalog.describe: table '" + table + "' was not found in the catalog.";
            }
        };
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    /**
     * Maps a described table onto the {@code catalog.describe} structured fields. A {@code null}
     * comment is a slot the entry omits rather than an empty string on the wire, the census writing
     * {@code NULL} precisely so a reader can tell a blank comment from an absent one.
     */
    private static void mapResolvedTable(Map<String, Object> fields, CatalogQueries.TableDetail table) {
        fields.put("schema", table.schema());
        fields.put("name", table.name());
        McpWire.putIfNotNull(fields, "comment", table.comment());

        var columns = new ArrayList<Map<String, Object>>(table.columns().size());
        for (var c : table.columns()) {
            var col = new LinkedHashMap<String, Object>();
            col.put("sqlName", c.sqlName());
            col.put("javaName", c.javaName());
            col.put("sqlType", c.sqlType());
            col.put("nullable", c.nullable());
            McpWire.putIfNotNull(col, "comment", c.comment());
            columns.add(col);
        }
        fields.put("columns", columns);

        table.primaryKey().ifPresent(pk -> fields.put("primaryKey", mapKey(pk)));
        fields.put("uniqueKeys", table.uniqueKeys().stream().map(GraphitronMcpServer::mapKey).toList());
        fields.put("indexes", table.indexes().stream()
            .map(i -> Map.<String, Object>of("name", i.name(), "columns", i.columns()))
            .toList());

        var foreignKeys = new LinkedHashMap<String, Object>();
        foreignKeys.put("outgoing", table.outgoing().stream()
            .map(fk -> mapForeignKey(fk, "targetTable"))
            .toList());
        foreignKeys.put("incoming", table.incoming().stream()
            .map(fk -> mapForeignKey(fk, "sourceTable"))
            .toList());
        fields.put("foreignKeys", foreignKeys);
    }

    /**
     * One foreign key's wire entry. The neighbour's slot is named by the direction, an outgoing key
     * reporting what it targets and an incoming one what declares it, which is the one thing the two
     * directions do not share.
     *
     * <p>The two column arrays are this entry's transposition of the read's column pairs, taken here
     * because the wire asks for two arrays and the read guarantees a pairing. Every pair contributes to
     * both arrays at the same index, so the two cannot come out of step.
     */
    private static Map<String, Object> mapForeignKey(
        CatalogQueries.ForeignKeyEntry fk, String neighbourSlot
    ) {
        var m = new LinkedHashMap<String, Object>();
        m.put("constraintName", fk.constraintName());
        m.put(neighbourSlot, fk.otherTable());
        m.put("columns", fk.columnPairs().stream()
            .map(CatalogQueries.ColumnPair::column).toList());
        m.put("targetColumns", fk.columnPairs().stream()
            .map(CatalogQueries.ColumnPair::targetColumn).toList());
        return m;
    }

    private static Map<String, Object> mapKey(CatalogQueries.KeyEntry key) {
        var m = new LinkedHashMap<String, Object>();
        m.put("constraintName", key.constraintName());
        m.put("columns", key.columns());
        return m;
    }

    // ---- code tools (services / conditions / records) ----

    /** Common {@code {name?, limit?, cursor?}} input schema shared by the three code tools. */
    private static Map<String, Object> nameLimitCursorSchema(String nameDescription) {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "name", Map.of("type", "string", "description", nameDescription),
                "limit", Map.of("type", "integer",
                    "description", "Maximum entries per page (default " + CodeTools.DEFAULT_LIMIT + ")."),
                "cursor", Map.of("type", "string",
                    "description", "Opaque page cursor from a prior call's nextCursor.")));
    }

    /**
     * {@code services}: the consumer service / condition-host classes the schema wires to. Reads
     * {@link Workspace#catalog()} external references joined with {@link Workspace#sourceIndex()}
     * for class source locations, both live on every call.
     */
    private static McpServerFeatures.SyncToolSpecification servicesTool(Workspace workspace) {
        var tool = McpSchema.Tool.builder("services",
                nameLimitCursorSchema("Case-insensitive substring filter on the class FQN."))
            .title("List service classes")
            .description("Lists the consumer Java classes the schema wires to as services, each with "
                + "its public methods (name, return type, parameters) and stable method-ref IDs "
                + "(fqcn#method/arity). Condition-returning methods appear here too; the conditions "
                + "tool is the condition-filtered view. Each class carries its source location when "
                + "the .java source index has it; an absent location reflects an un-rewalked source "
                + "(the source cadence is independent of the build cadence), not a missing class.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> CodeTools.servicesResult(
                workspace.catalog().externalReferences(), workspace.sourceIndex(), request.arguments()))
            .build();
    }

    /**
     * {@code conditions}: the methods whose typed {@code returnsCondition} fact is set (return type
     * is jOOQ {@code org.jooq.Condition}), classified at the parse boundary in
     * {@code ClasspathScanner}. Same live reads and source-location join as {@code services}, keyed
     * per method.
     */
    private static McpServerFeatures.SyncToolSpecification conditionsTool(Workspace workspace) {
        var tool = McpSchema.Tool.builder("conditions",
                nameLimitCursorSchema("Case-insensitive substring filter on the owning class FQN."))
            .title("List condition methods")
            .description("Lists the consumer methods returning a jOOQ Condition (classified exactly "
                + "from the un-erased return type, so a consumer's own type named Condition is not "
                + "mistaken for one), each with its owning class, parameters, and stable method-ref "
                + "ID. Each carries its source location when the .java source index has it; an absent "
                + "location reflects an un-rewalked source or an overload the (class, name, arity) key "
                + "could not disambiguate, not an error.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> CodeTools.conditionsResult(
                workspace.catalog().externalReferences(), workspace.sourceIndex(), request.arguments()))
            .build();
    }

    /**
     * {@code records}: the consumer classes with a non-empty record-component list (a Java
     * {@code record} / POJO backing), each with its components and a class source location.
     */
    private static McpServerFeatures.SyncToolSpecification recordsTool(Workspace workspace) {
        var tool = McpSchema.Tool.builder("records",
                nameLimitCursorSchema("Case-insensitive substring filter on the class FQN."))
            .title("List record classes")
            .description("Lists the consumer Java record classes the schema can bind to, each with "
                + "its components (name, display type) and source location when the .java source "
                + "index has it.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> CodeTools.recordsResult(
                workspace.catalog().externalReferences(), workspace.sourceIndex(), request.arguments()))
            .build();
    }

    // ---- schema tool ----

    /**
     * {@code schema}: the current SDL types, their classifications, backing shapes, field
     * classifications, and definition locations off {@link Workspace#snapshot()}, joined with
     * {@code @node} metadata off {@link Workspace#catalog()} (same build cadence). Both reads are
     * live on every call. The session's {@link StoreHandle} answers one field the projection no
     * longer carries: what a class-backed type's members are, which is a fact about a class on the
     * classpath rather than about this snapshot.
     */
    private static McpServerFeatures.SyncToolSpecification schemaTool(
        Workspace workspace, StoreHandle storeHandle
    ) {
        var tool = McpSchema.Tool.builder("schema", Map.of(
                "type", "object",
                "properties", Map.of(
                    "type", Map.of("type", "string",
                        "description", "Narrow to one SDL type, returning its fields in full."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum types per page (default " + SchemaView.DEFAULT_LIMIT + ")."),
                    "cursor", Map.of("type", "string",
                        "description", "Opaque page cursor from a prior call's nextCursor."))))
            .title("Describe the schema")
            .description("Lists the current GraphQL types with their classification, backing shape, "
                + "field classifications (keyed by the Type.field coordinate), @node metadata, and "
                + "definition location, paged via an opaque cursor; pass type to narrow to one. "
                + "Reflects the latest successful build snapshot, reporting its availability and "
                + "freshness; types are empty until the first build succeeds.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> SchemaView.schemaResult(
                workspace.snapshot(), workspace.catalog().nodeMetadata(), storeHandle,
                request.arguments()))
            .build();
    }

    // ---- diagnostics tool ----

    /**
     * {@code diagnostics}: the current diagnostics as a projection of the fact store's
     * {@code diagnostic} view, read through the session's {@link StoreHandle} and scoped to its
     * graph, with the live snapshot's availability / freshness reported alongside so an agent
     * can tell whether the diagnostics are current relative to the schema it just read. The
     * {@code where} filter shares {@code diagnostics.aggregate}'s dimension vocabulary and
     * null-safe translation, so an aggregate group's key is this tool's exact drill-down.
     */
    private static McpServerFeatures.SyncToolSpecification diagnosticsTool(
        Workspace workspace, StoreHandle storeHandle
    ) {
        var tool = McpSchema.Tool.builder("diagnostics", Map.of(
                "type", "object",
                "properties", Map.of(
                    "severity", Map.of("type", "string",
                        "description", "Filter to one severity: \"error\" or \"warning\"."),
                    "coordinate", Map.of("type", "string",
                        "description", "Filter to one schema coordinate (a type name or Type.field)."),
                    "where", Map.of("type", "object",
                        "description", "Filter on the diagnostics.aggregate dimensions, e.g. "
                            + "{\"attemptKind\": \"COLUMN\", \"attempt\": \"id\"}. Null-safe: a null "
                            + "value selects the rows where that dimension is absent. Paste an "
                            + "aggregate group's key here to read exactly that group's entries."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum entries per page (default " + DiagnosticsTool.DEFAULT_LIMIT + ")."),
                    "cursor", Map.of("type", "string",
                        "description", "Opaque page cursor from a prior call's nextCursor."))))
            .title("List schema diagnostics")
            .description("Lists the current diagnostics (severity, source, coordinate, message, "
                + "rejection kind, location), paged via an opaque cursor, with optional severity and "
                + "coordinate filters plus a where filter over the same dimensions "
                + "diagnostics.aggregate groups on, so a group's key reads back exactly that "
                + "group's entries. Each entry carries a source: \"schema\" for validation "
                + "rejections, \"compile\" for graphitron:dev generated-code compile errors. Reports "
                + "the snapshot's availability and freshness alongside so you can tell whether the "
                + "diagnostics are current relative to the schema. Closes the authoring loop: edit, "
                + "then read your own diagnostics back. When the count runs past a page, start with "
                + "diagnostics.aggregate instead.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> DiagnosticsTool.diagnosticsResult(
                storeHandle, workspace.snapshot(), request.arguments()))
            .build();
    }

    /**
     * {@code diagnostics.aggregate}: counts over the same {@code diagnostic} view, grouped by
     * the closed dimension vocabulary, returning no entries at all. The zero-argument call is
     * the triage preset (the actionable / deferred headline with kind sub-counts); everything
     * else is composed from {@code groupBy} / {@code where}. The dimension gloss in the
     * description renders from the declared typed-key / location-derived partition, so the
     * documentation cannot drift from the enum.
     */
    private static McpServerFeatures.SyncToolSpecification diagnosticsAggregateTool(
        Workspace workspace, StoreHandle storeHandle
    ) {
        var tool = McpSchema.Tool.builder("diagnostics.aggregate", Map.of(
                "type", "object",
                "properties", Map.of(
                    "groupBy", Map.of("type", "array",
                        "items", Map.of("type", "string",
                            "enum", DiagnosticFacets.Dimension.wireNames()),
                        "description", "Ordered dimensions forming the composite group key. "
                            + "Omit for the triage preset (actionable, kind)."),
                    "where", Map.of("type", "object",
                        "description", "Filter on the same dimensions, e.g. {\"source\": \"schema\", "
                            + "\"directory\": \"file:///…/features\"}. Null-safe: a null value "
                            + "selects the rows where that dimension is absent."),
                    "minCount", Map.of("type", "integer",
                        "description", "Tail threshold: groups below it are elided and reported "
                            + "in the elision accounting, never silently dropped (default 1)."),
                    "examples", Map.of("type", "integer",
                        "description", "Example coordinates and files per group (default "
                            + DiagnosticFacets.DEFAULT_EXAMPLES + ", max "
                            + DiagnosticFacets.MAX_EXAMPLES + ")."),
                    "orderBy", Map.of("type", "string",
                        "description", "\"count\" (default, largest first) or \"key\"."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum groups returned (default "
                            + DiagnosticFacets.DEFAULT_GROUP_LIMIT + ", capped at "
                            + DiagnosticFacets.MAX_GROUP_LIMIT + "); elided groups are counted, "
                            + "never silently dropped."))))
            .title("Aggregate schema diagnostics")
            .description("Counts diagnostics grouped by the dimensions you name, and returns no "
                + "entries, so the result stays small however broken the schema is. Call it with "
                + "no arguments for the triage view: how much of the schema you can fix yourself, "
                + "and how much is shapes graphitron does not generate yet. Then set groupBy to "
                + "pivot on your own question. Every group carries an exact count, a few example "
                + "coordinates, and the files it spans. When minCount or limit elides groups, the "
                + "response says how many were elided and their combined count, so a truncated "
                + "aggregate never reads as complete. Filter with where on the same dimensions you "
                + "group on, then hand a group's key to the diagnostics tool to read that group's "
                + "entries without paging the rest. " + DiagnosticFacets.dimensionGloss())
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> DiagnosticFacets.aggregateResult(
                storeHandle, workspace.snapshot(), request.arguments()))
            .build();
    }

    // ---- catalog.search (semantic catalog discovery) ----

    /** Default {@code catalog.search} top-k: semantic discovery is "find the table I can't name", not enumeration. */
    private static final int DEFAULT_SEARCH_LIMIT = 10;

    /**
     * {@code catalog.search}: fuzzy, semantic discovery over the database catalog by names and
     * comments. The semantic counterpart to {@code catalog.tables} / {@code catalog.describe}: those
     * answer "describe the table I named", this answers "find the table I can only describe". Drives
     * the self-observing {@link #catalogSearchIndex}, which composes its corpus from the same census
     * those two read and refreshes its persisted Lucene index when the content changes. Hits return by
     * the same schema-qualified SQL id {@code catalog.describe} accepts, so discovery hands off to
     * description.
     */
    private McpServerFeatures.SyncToolSpecification catalogSearchTool(
        StoreHandle store, StoreReader reader
    ) {
        var tool = McpSchema.Tool.builder("catalog.search", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string",
                        "description", "Natural-language description of the data you are looking for."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum tables to return (default " + DEFAULT_SEARCH_LIMIT + ").")),
                "required", List.of("query")))
            .title("Search the catalog semantically")
            .description("Fuzzy, semantic discovery over the database catalog by table and column "
                + "names and comments. Ask in natural language (\"where are customer addresses "
                + "stored?\") and get back the most relevant tables ranked by similarity, each by its "
                + "schema-qualified SQL name; feed a hit's id straight into catalog.describe for the "
                + "full column / key / foreign-key detail. Semantic search is much stronger when your "
                + "jOOQ codegen captured table and column comments; without them it works on names "
                + "alone. The index warms in the background at dev startup and refreshes after a "
                + "schema change; a search during a refresh reports that it is warming and points you "
                + "at the structured catalog.* tools meanwhile.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) ->
                catalogSearchResult(catalogSearchIndex, store, reader, request.arguments()))
            .build();
    }

    /**
     * The two absences are different answers, and the order is what makes them so. No store is a
     * refusal: the corpus is the census, so an index over no census is not an index that will be ready
     * shortly, and reporting the warming degradation there would tell an agent to try again on a
     * server where retrying cannot help. No RAG over a store that is present is the warming
     * degradation, which is the honest reading of an index that has nothing to rank with, and it
     * routes to the structured catalog tools that can answer from the same rows.
     */
    static McpSchema.CallToolResult catalogSearchResult(
        CatalogSearchIndex index, StoreHandle store, StoreReader reader, Map<String, Object> args
    ) {
        if (store == null || reader == null) {
            return DiagnosticFacets.refusal("catalog.search");
        }
        if (index == null) {
            // No RAG wired (the structured-tool tests): the tool is advertised but degrades,
            // mirroring docs.search, so an agent falls back to the structured catalog.* tools.
            return McpSchema.CallToolResult.builder()
                .addTextContent(WarmState.degradationMessage(new WarmState.Warming<>()))
                .structuredContent(Map.of("status", "warming", "results", List.of()))
                .build();
        }
        Optional<String> query = McpWire.stringArg(args, "query");
        if (query.isEmpty()) {
            return McpSchema.CallToolResult.builder()
                .addTextContent("catalog.search: a non-empty 'query' argument is required.")
                .structuredContent(Map.of("status", "invalid", "results", List.of()))
                .build();
        }
        int limit = McpWire.intArg(args, "limit", DEFAULT_SEARCH_LIMIT);
        if (limit < 1) {
            limit = DEFAULT_SEARCH_LIMIT;
        }
        return switch (index.search(query.get(), limit)) {
            case CatalogSearchIndex.SearchOutcome.Hits hits -> catalogSearchHits(query.get(), hits.hits());
            case CatalogSearchIndex.SearchOutcome.Degraded degraded -> McpSchema.CallToolResult.builder()
                .addTextContent(degraded.message())
                .structuredContent(Map.of("status", degraded.status(), "results", List.of()))
                .build();
        };
    }

    /**
     * Maps ranked hits onto the {@code catalog.search} result: each hit carries its stable
     * {@code schema.table} id (split back into {@code schema} / {@code name} for convenience), the
     * comment when codegen captured one, and the fused RRF score surfaced verbatim.
     */
    private static McpSchema.CallToolResult catalogSearchHits(String query, List<EmbeddingStore.Hit> hits) {
        var results = new ArrayList<Map<String, Object>>(hits.size());
        for (var hit : hits) {
            String[] qualified = McpWire.splitQualifiedTable(hit.id());
            var entry = new LinkedHashMap<String, Object>();
            entry.put("id", hit.id());
            entry.put("schema", qualified[0]);
            entry.put("name", qualified[1]);
            if (hit.payload() != null && !hit.payload().isBlank()) {
                entry.put("comment", hit.payload());
            }
            entry.put("score", hit.score());
            results.add(entry);
        }
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", "ready");
        fields.put("results", results);
        String summary = "catalog.search: " + hits.size() + " table(s) for \"" + query + "\""
            + (hits.isEmpty() ? "" : "; top match " + hits.get(0).id()) + ".";
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    // ---- directives resource ----

    /**
     * The {@code directives} resource: the directive-vocabulary cheat-sheet, composed from the
     * bundled grammar (frozen, projected once at construction) unioned with the live snapshot's
     * user-declared directives. A resource, not a tool, because the directive grammar is shape, not
     * state. Re-reads reflect the latest snapshot, degrading to the bundled grammar alone when no
     * build has succeeded.
     */
    private static McpServerFeatures.SyncResourceSpecification directivesResource(
        Workspace workspace, List<DirectiveShape> bundledDirectives
    ) {
        return new McpServerFeatures.SyncResourceSpecification(
            DirectivesResource.resource(),
            (exchange, request) -> DirectivesResource.read(bundledDirectives, workspace.snapshot()));
    }

    /**
     * Server version for the handshake. Read from the jar manifest's implementation version,
     * falling back to {@code "dev"} when absent (running from {@code target/classes} under tests
     * or an IDE) so the required {@code serverInfo.version} is never null. Cosmetic here: it
     * drives nothing, so the fallback is deliberately trivial.
     */
    private static String version() {
        String v = GraphitronMcpServer.class.getPackage().getImplementationVersion();
        return (v != null && !v.isBlank()) ? v : "dev";
    }

    /** Reads a bundled UTF-8 resource once at startup. A missing resource is a packaging error: fail loud. */
    private static String loadResource(String path) {
        try (InputStream in = GraphitronMcpServer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing bundled MCP resource: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled MCP resource: " + path, e);
        }
    }
}
