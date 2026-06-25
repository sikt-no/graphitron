package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
import no.sikt.graphitron.rewrite.catalog.CatalogBuilder;
import no.sikt.graphitron.rewrite.catalog.CatalogFacts;
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
 * loopback address. It serves the static {@code instructions} string returned in the MCP
 * {@code initialize} handshake, a single argument-less {@code about} prompt, and one liveness
 * {@code status} tool that reads the live generator model.
 *
 * <p>Mirrors the sibling {@code DevServer}'s transport-glue shape: one instance per Mojo
 * invocation, {@link AutoCloseable}, constructed with an {@link InetSocketAddress} so production
 * passes {@code 127.0.0.1:8488} and tests bind an ephemeral port. The bundled prose is read once
 * at startup from jar resources under {@code /mcp/}; it is shape, not state.
 *
 * <p>R361 — the server holds the same live {@link Workspace} handle the LSP {@code DevServer}
 * holds: the one instance {@code DevMojo} builds and the schema / classpath / source watchers
 * mutate in place on every save and recompile. The {@code status} tool reads
 * {@link Workspace#snapshot()} off that shared reference, so each call observes the latest
 * build state without any new trigger or refresh path. This is the foundational seam every
 * structured-tool slice (R118 slices 2-7) builds on; slice 1 ships only the liveness read.
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

    /**
     * Build and start the server on the supplied loopback address, holding the live
     * {@code workspace} the {@code status} tool reads its snapshot state off. A taken port
     * surfaces as an {@link IOException}; the caller translates it into a Mojo error. On any
     * startup failure the partially-built server is torn down before the exception propagates, so
     * nothing leaks.
     */
    public GraphitronMcpServer(InetSocketAddress address, Workspace workspace) throws IOException {
        String instructions = loadResource("/mcp/instructions.txt");
        String aboutText = loadResource("/mcp/about.md");

        var transportProvider = HttpServletStreamableServerTransportProvider.builder()
            .mcpEndpoint(MCP_ENDPOINT)
            .build();

        // R368 — the bundled directive grammar projected once off the frozen vocabulary registry
        // (shape, not state). The directives resource unions this with the live snapshot's
        // user-declared directives on every read; the bundled half never changes, so it is computed
        // here rather than per read. The projection carries applicable locations via the R368
        // DirectiveShape widening.
        List<DirectiveShape> bundledDirectives =
            CatalogBuilder.buildSnapshot(workspace.vocabulary().registry()).directives();

        // Build the sync server before mounting the servlet: this wires the session factory into
        // the transport provider, so it is ready before Jetty accepts the first request. The
        // tools(false) / resources(false, false) booleans are the listChanged (and, for resources,
        // subscribe) capabilities; the seam never mutates the tool or resource list at runtime, so
        // they stay false even though the tools and resource read live state.
        this.mcpServer = McpServer.sync(transportProvider)
            .serverInfo(SERVER_NAME, version())
            .instructions(instructions)
            .capabilities(McpSchema.ServerCapabilities.builder()
                .prompts(false).tools(false).resources(false, false).build())
            .prompts(aboutPrompt(aboutText))
            .tools(
                statusTool(workspace),
                catalogTablesTool(workspace), catalogDescribeTool(workspace),
                servicesTool(workspace), conditionsTool(workspace), recordsTool(workspace),
                schemaTool(workspace), diagnosticsTool(workspace))
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
     * The single liveness {@code status} tool: the smallest call that proves the live model read
     * works end-to-end. It takes no arguments and reads {@link Workspace#snapshot()} off the live
     * handle on every call, so the answer reflects the latest build state (R361 D2).
     *
     * <p>The snapshot is reported on its two orthogonal axes rather than a flattened tri-state:
     * {@code availability} ({@code Built} vs {@code Unavailable}) and {@code freshness}
     * ({@code Current} vs {@code Previous}, absent when unavailable). The two fields are mapped
     * exhaustively off the {@link LspSchemaSnapshot} sealed permits, so the MCP view never
     * re-derives a fork the model owns. Domain counts (tables, references, diagnostics) are
     * deliberately out of scope: those are agent-facing wire schemas the later structured-tool
     * slices own.
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
     * R362 — {@code catalog.tables}: lists the database tables the schema wires to, reading
     * {@link Workspace#catalogFacts()} off the live handle on every call so answers reflect the
     * latest build. Optional {@code schema} (exact, case-insensitive) and {@code name}
     * (case-insensitive substring on the SQL table name) filters; {@code limit} + opaque
     * {@code cursor} page the stable schema-qualified-name ordering, with a {@code nextCursor} on
     * the result until the last page.
     */
    private static McpServerFeatures.SyncToolSpecification catalogTablesTool(Workspace workspace) {
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
                + "schema and SQL-name-substring filters, paged via an opaque cursor. Each table "
                + "carries its schema, SQL name, and comment when jOOQ codegen captured one. SQL "
                + "names drive discovery; use catalog.describe for a single table's columns, keys, "
                + "indexes, and foreign keys.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> catalogTablesResult(workspace.catalogFacts(), request.arguments()))
            .build();
    }

    static McpSchema.CallToolResult catalogTablesResult(CatalogFacts facts, Map<String, Object> args) {
        Optional<String> schema = McpWire.stringArg(args, "schema");
        Optional<String> name = McpWire.stringArg(args, "name");

        var all = facts.tables(schema, name);
        var paged = McpWire.page(all, args, DEFAULT_TABLES_LIMIT);

        var tableList = new ArrayList<Map<String, Object>>(paged.items().size());
        for (var t : paged.items()) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("schema", t.schema());
            entry.put("name", t.name());
            t.comment().ifPresent(c -> entry.put("comment", c));
            tableList.add(entry);
        }

        var fields = new LinkedHashMap<String, Object>();
        fields.put("tables", tableList);
        paged.nextCursor().ifPresent(c -> fields.put("nextCursor", c));

        String summary = "catalog.tables: " + all.size() + " table(s)"
            + schema.map(s -> " in schema '" + s + "'").orElse("")
            + name.map(n -> " matching '" + n + "'").orElse("")
            + "; showing " + paged.items().size()
            + (paged.nextCursor().isPresent() ? " (more available)" : "") + ".";
        return McpSchema.CallToolResult.builder()
            .addTextContent(summary)
            .structuredContent(fields)
            .build();
    }

    /**
     * R362 — {@code catalog.describe}: one table's columns (SQL and Java names, SQL types,
     * nullability, comments when codegen captured them), primary / unique keys, indexes, and foreign
     * keys in both directions with their column pairs. Reads {@link Workspace#catalogFacts()} live.
     * {@code table} accepts a bare or schema-qualified SQL name; {@code schema} is the alternative to
     * inline qualification. An unqualified name two schemas carry returns a structured {@code ambiguous}
     * result; an unknown name returns {@code notFound}.
     */
    private static McpServerFeatures.SyncToolSpecification catalogDescribeTool(Workspace workspace) {
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
                + "nullability, and comments when jOOQ codegen captured them), the primary key, "
                + "unique keys, indexes, and foreign keys in and out (with their column pairs). "
                + "Foreign-key endpoints name neighbouring tables by their schema-qualified SQL name. "
                + "Column comments appear only when codegen ran with comments enabled; their absence "
                + "reflects codegen configuration, not a missing database comment. An ambiguous "
                + "unqualified name returns the candidate schemas to re-call qualified.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> catalogDescribeResult(workspace.catalogFacts(), request.arguments()))
            .build();
    }

    static McpSchema.CallToolResult catalogDescribeResult(CatalogFacts facts, Map<String, Object> args) {
        String table = McpWire.stringArg(args, "table").orElse("");
        Optional<String> schema = McpWire.stringArg(args, "schema");

        var fields = new LinkedHashMap<String, Object>();
        String summary = switch (facts.resolve(table, schema)) {
            case CatalogFacts.TableResolution.Resolved r -> {
                fields.put("resolution", "resolved");
                mapResolvedTable(fields, r.table());
                yield "catalog.describe: " + r.table().qualifiedName() + " ("
                    + r.table().columns().size() + " column(s)).";
            }
            case CatalogFacts.TableResolution.Ambiguous a -> {
                fields.put("resolution", "ambiguous");
                fields.put("schemas", List.copyOf(a.schemas()));
                yield "catalog.describe: table '" + table + "' is ambiguous, carried by schemas "
                    + a.schemas() + "; re-call qualified (e.g. \"" + a.schemas().get(0) + "." + table + "\").";
            }
            case CatalogFacts.TableResolution.NotFound ignored -> {
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

    /** Maps a resolved {@link CatalogFacts.Table} onto the {@code catalog.describe} structured fields. */
    private static void mapResolvedTable(Map<String, Object> fields, CatalogFacts.Table table) {
        fields.put("schema", table.schema());
        fields.put("name", table.name());
        table.comment().ifPresent(c -> fields.put("comment", c));

        var columns = new ArrayList<Map<String, Object>>(table.columns().size());
        for (var c : table.columns()) {
            var col = new LinkedHashMap<String, Object>();
            col.put("sqlName", c.sqlName());
            col.put("javaName", c.javaName());
            col.put("sqlType", c.sqlType());
            col.put("nullable", c.nullable());
            c.comment().ifPresent(cm -> col.put("comment", cm));
            columns.add(col);
        }
        fields.put("columns", columns);

        table.primaryKey().ifPresent(pk -> fields.put("primaryKey", mapKey(pk)));
        fields.put("uniqueKeys", table.uniqueKeys().stream().map(GraphitronMcpServer::mapKey).toList());
        fields.put("indexes", table.indexes().stream()
            .map(i -> Map.<String, Object>of("name", i.name(), "columns", i.columns()))
            .toList());

        var foreignKeys = new LinkedHashMap<String, Object>();
        foreignKeys.put("outgoing", table.foreignKeys().outgoing().stream()
            .map(fk -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("constraintName", fk.constraintName());
                m.put("targetTable", fk.targetTable());
                m.put("columns", fk.columns());
                m.put("targetColumns", fk.targetColumns());
                return (Map<String, Object>) m;
            })
            .toList());
        foreignKeys.put("incoming", table.foreignKeys().incoming().stream()
            .map(fk -> {
                var m = new LinkedHashMap<String, Object>();
                m.put("constraintName", fk.constraintName());
                m.put("sourceTable", fk.sourceTable());
                m.put("columns", fk.columns());
                m.put("targetColumns", fk.targetColumns());
                return (Map<String, Object>) m;
            })
            .toList());
        fields.put("foreignKeys", foreignKeys);
    }

    private static Map<String, Object> mapKey(CatalogFacts.Key key) {
        var m = new LinkedHashMap<String, Object>();
        m.put("constraintName", key.constraintName());
        m.put("columns", key.columns());
        return m;
    }

    // ---- R368: code tools (services / conditions / records) ----

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
     * {@code services}: the consumer service / condition-host classes the schema wires to, each with
     * its callable methods (condition-returning methods included; the same class is both a service
     * host and a condition host). Reads {@link Workspace#catalog()} external references joined with
     * {@link Workspace#sourceIndex()} for class source locations, both live on every call.
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

    // ---- R368: schema tool ----

    /**
     * {@code schema}: the current SDL types, their classifications, backing shapes, field
     * classifications, and definition locations off {@link Workspace#snapshot()}, joined with
     * {@code @node} metadata off {@link Workspace#catalog()} (same build cadence). Both reads are
     * live on every call; the snapshot + catalog join is same-cadence (R361 D3).
     */
    private static McpServerFeatures.SyncToolSpecification schemaTool(Workspace workspace) {
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
                workspace.snapshot(), workspace.catalog().nodeMetadata(), request.arguments()))
            .build();
    }

    // ---- R368: diagnostics tool ----

    /**
     * {@code diagnostics}: the current validation errors and warnings off
     * {@link Workspace#validationReport()}, with the live snapshot's availability / freshness
     * reported alongside so an agent can tell whether the diagnostics are current relative to the
     * schema it just read.
     */
    private static McpServerFeatures.SyncToolSpecification diagnosticsTool(Workspace workspace) {
        var tool = McpSchema.Tool.builder("diagnostics", Map.of(
                "type", "object",
                "properties", Map.of(
                    "severity", Map.of("type", "string",
                        "description", "Filter to one severity: \"error\" or \"warning\"."),
                    "coordinate", Map.of("type", "string",
                        "description", "Filter to one schema coordinate (a type name or Type.field)."),
                    "limit", Map.of("type", "integer",
                        "description", "Maximum entries per page (default " + DiagnosticsTool.DEFAULT_LIMIT + ")."),
                    "cursor", Map.of("type", "string",
                        "description", "Opaque page cursor from a prior call's nextCursor."))))
            .title("List schema diagnostics")
            .description("Lists the current validation errors and warnings (severity, coordinate, "
                + "message, rejection kind, location), paged via an opaque cursor, with optional "
                + "severity and coordinate filters. Reports the snapshot's availability and freshness "
                + "alongside so you can tell whether the diagnostics are current relative to the "
                + "schema. Closes the authoring loop: edit, then read your own diagnostics back.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> DiagnosticsTool.diagnosticsResult(
                workspace.validationReport(), workspace.snapshot(), request.arguments()))
            .build();
    }

    // ---- R368: directives resource ----

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
