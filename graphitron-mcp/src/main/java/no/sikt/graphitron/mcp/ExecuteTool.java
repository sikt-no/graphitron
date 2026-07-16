package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code execute} MCP tool: runs a GraphQL query or mutation against the generated
 * resolvers in-process (through {@link DevQueryExecutor} and the generated
 * {@code GraphitronDevExecutor}) on the configured dev database, and returns the
 * {@code ExecutionResult.toSpecification()} JSON. Mutations always roll back (the executor pins
 * the {@code ROLLBACK_ONLY} commit policy), so exploration never persists a write.
 *
 * <p>The tool exists only when a dev database is configured: {@link GraphitronMcpServer} registers
 * it conditionally, the stronger form of the degrade-gracefully posture (the RAG tools stay
 * advertised and degrade; this tool is simply absent).
 *
 * <p>Claims resolution happens per call, so a {@code @/path/to/file} payload picks up file edits
 * without a dev-loop restart. A per-call {@code claims} argument is honored only when the config
 * opted in ({@code allowClaimsOverride}); the default-off posture keeps one pinned identity on
 * shared or sensitive dev databases.
 */
public final class ExecuteTool {

    /**
     * Everything the tool needs, all plain values threaded from the dev Mojo: where the generated
     * executor lives ({@code wiring}), the dev database coordinates ({@code db}, with {@code claims}
     * still in its raw inline-or-{@code @file} form), and whether a per-call claims override is
     * allowed (default off).
     */
    public record Config(DevQueryExecutor.Wiring wiring, DevQueryExecutor.DbConfig db, boolean allowClaimsOverride) {
        public Config {
            Objects.requireNonNull(wiring, "wiring");
            Objects.requireNonNull(db, "db");
        }
    }

    /**
     * The invocation seam: production is {@link DevQueryExecutor#execute}, tests substitute a
     * fake so the handler logic (argument validation, claims resolution, error wrapping) is
     * testable without a compiled executor or a database.
     */
    @FunctionalInterface
    interface Invoker {
        String execute(DevQueryExecutor.Wiring wiring, DevQueryExecutor.DbConfig db, String query,
                Map<String, Object> variables, Map<String, Object> contextArgs)
                throws DevQueryExecutor.DevExecutionException;
    }

    private final Config config;
    private final Invoker invoker;

    ExecuteTool(Config config) {
        this(config, DevQueryExecutor::execute);
    }

    ExecuteTool(Config config, Invoker invoker) {
        this.config = config;
        this.invoker = invoker;
    }

    McpServerFeatures.SyncToolSpecification specification() {
        var tool = McpSchema.Tool.builder("execute", Map.of(
                "type", "object",
                "properties", Map.of(
                    "query", Map.of("type", "string",
                        "description", "The GraphQL query or mutation to execute."),
                    "variables", Map.of("type", "object",
                        "description", "The operation's variables, as a JSON object."),
                    "contextArgs", Map.of("type", "object",
                        "description", "One entry per contextArgument the schema declares "
                            + "(name to value); required when the schema declares any."),
                    "claims", Map.of("type", "string",
                        "description", "Per-call claims override (opt-in via configuration; "
                            + "rejected when not enabled).")),
                "required", List.of("query")))
            .title("Execute a GraphQL operation in-process")
            .description("Runs a GraphQL query or mutation against the generated resolvers "
                + "in-process on the configured dev database, no app server needed, and returns "
                + "the execution result JSON (data plus GraphQL errors). Mutations run in a "
                + "transaction that is always rolled back, so you can probe writes freely without "
                + "changing data. If the schema mounts session identity (<sessionState>), the "
                + "configured claims payload is handed to the real connect hook, so row-level "
                + "security applies exactly as in production.")
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(tool)
            .callHandler((exchange, request) -> executeResult(request.arguments()))
            .build();
    }

    McpSchema.CallToolResult executeResult(Map<String, Object> args) {
        var query = McpWire.stringArg(args, "query");
        if (query.isEmpty()) {
            return error("The execute tool requires a 'query' argument carrying the GraphQL "
                + "operation to run.");
        }
        Map<String, Object> variables = mapArg(args, "variables");
        Map<String, Object> contextArgs = mapArg(args, "contextArgs");

        var claimsOverride = McpWire.stringArg(args, "claims");
        if (claimsOverride.isPresent() && !config.allowClaimsOverride()) {
            return error("Per-call claims are not enabled for this dev database. The configured "
                + "claims payload is pinned; opt in via the plugin's <devDatabase> "
                + "<allowClaimsOverride>true</allowClaimsOverride> (or "
                + "GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE=true) to probe other identities.");
        }

        String claims;
        try {
            claims = resolveClaims(claimsOverride.orElse(config.db().claims()));
        } catch (IOException e) {
            return error("Could not read the claims payload file: " + e.getMessage());
        }

        var db = new DevQueryExecutor.DbConfig(
            config.db().url(), config.db().user(), config.db().password(), config.db().dialect(), claims);
        try {
            String resultJson = invoker.execute(config.wiring(), db, query.get(), variables, contextArgs);
            return success(resultJson);
        } catch (DevQueryExecutor.DevExecutionException e) {
            // The hook is the validator and its errors are the feedback loop: the executor-side
            // message (connect-hook rejection, fail-loud missing claims, SQL failure) is the tool
            // result, verbatim.
            return error(e.getMessage());
        }
    }

    /**
     * Resolves the raw claims value: {@code @/path/to/file} reads the file (per call, so edits
     * apply without a restart, and tokens stay out of environment listings); anything else passes
     * through untouched, including {@code null} (the executor decides whether claims are required,
     * per its schema's {@code <sessionState>}).
     */
    static String resolveClaims(String raw) throws IOException {
        if (raw == null || !raw.startsWith("@")) {
            return raw;
        }
        return Files.readString(Path.of(raw.substring(1))).strip();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapArg(Map<String, Object> args, String name) {
        if (args == null) {
            return null;
        }
        Object value = args.get(name);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static McpSchema.CallToolResult success(String resultJson) {
        var builder = McpSchema.CallToolResult.builder().addTextContent(resultJson);
        try {
            // The executor's JSON string, re-projected as structured content through the MCP
            // SDK's own mapper abstraction (no JSON dependency of this module's own).
            builder.structuredContent(Map.of(
                "status", "ok",
                "result", McpJsonDefaults.getMapper().readValue(resultJson, Map.class)));
        } catch (IOException e) {
            // The text content already carries the payload; structured content is best-effort.
            builder.structuredContent(Map.of("status", "ok"));
        }
        return builder.build();
    }

    private static McpSchema.CallToolResult error(String message) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("status", "error");
        fields.put("message", message);
        return McpSchema.CallToolResult.builder()
            .addTextContent(message)
            .structuredContent(fields)
            .isError(true)
            .build();
    }
}
