package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.lsp.state.Workspace;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

        // Build the sync server before mounting the servlet: this wires the session factory into
        // the transport provider, so it is ready before Jetty accepts the first request. The
        // tools(false) boolean is the listChanged capability; the seam never mutates the tool
        // list at runtime, so it stays false even though the tools read live state.
        this.mcpServer = McpServer.sync(transportProvider)
            .serverInfo(SERVER_NAME, version())
            .instructions(instructions)
            .capabilities(McpSchema.ServerCapabilities.builder().prompts(false).tools(false).build())
            .prompts(aboutPrompt(aboutText))
            .tools(statusTool(workspace))
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
