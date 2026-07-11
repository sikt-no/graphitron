package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ExecuteTool}'s handler logic through the {@link ExecuteTool.Invoker}
 * seam: argument validation, per-call {@code @file} claims resolution, the default-off claims
 * override, and error wrapping. The real invoker ({@link DevQueryExecutor}) is covered by
 * {@link DevQueryExecutorTest}; the wire-level advertisement is covered by
 * {@code GraphitronMcpServerTest}.
 */
class ExecuteToolTest {

    @TempDir
    Path tempDir;

    private static ExecuteTool.Config config(String claims, boolean allowClaimsOverride) {
        return new ExecuteTool.Config(
            new DevQueryExecutor.Wiring("com.example", Path.of("target/graphitron-classes"), List.of()),
            new DevQueryExecutor.DbConfig("jdbc:fake:dev", "dev", "secret", "POSTGRES", claims),
            allowClaimsOverride);
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(McpSchema.CallToolResult result) {
        return (Map<String, Object>) result.structuredContent();
    }

    @Test
    void happyPath_returnsTheExecutorJsonAsTextAndParsedStructuredContent() {
        var tool = new ExecuteTool(config("{\"sub\":\"u1\"}", false),
            (wiring, db, query, variables, contextArgs) -> "{\"data\":{\"ping\":1}}");
        var result = tool.executeResult(Map.of("query", "{ ping }"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("{\"data\":{\"ping\":1}}");
        assertThat(structured(result)).containsEntry("status", "ok");
        assertThat(structured(result).get("result"))
            .isEqualTo(Map.of("data", Map.of("ping", 1)));
    }

    @Test
    void argumentsPassThroughToTheInvoker() {
        var seen = new AtomicReference<Object[]>();
        var tool = new ExecuteTool(config("configured-claims", false),
            (wiring, db, query, variables, contextArgs) -> {
                seen.set(new Object[] {db.claims(), query, variables, contextArgs});
                return "{}";
            });
        tool.executeResult(Map.of(
            "query", "mutation { x }",
            "variables", Map.of("a", 1),
            "contextArgs", Map.of("userId", "u1")));

        assertThat(seen.get()[0]).isEqualTo("configured-claims");
        assertThat(seen.get()[1]).isEqualTo("mutation { x }");
        assertThat(seen.get()[2]).isEqualTo(Map.of("a", 1));
        assertThat(seen.get()[3]).isEqualTo(Map.of("userId", "u1"));
    }

    @Test
    void missingQuery_isAnErrorWithoutInvoking() {
        var tool = new ExecuteTool(config(null, false),
            (wiring, db, query, variables, contextArgs) -> {
                throw new AssertionError("must not invoke");
            });
        var result = tool.executeResult(Map.of());

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("'query'");
        assertThat(structured(result)).containsEntry("status", "error");
    }

    @Test
    void atFileClaims_areResolvedPerCall_soFileEditsApplyWithoutARestart() throws Exception {
        Path claimsFile = tempDir.resolve("claims.json");
        Files.writeString(claimsFile, "{\"sub\":\"first\"}\n");
        var seen = new AtomicReference<String>();
        var tool = new ExecuteTool(config("@" + claimsFile, false),
            (wiring, db, query, variables, contextArgs) -> {
                seen.set(db.claims());
                return "{}";
            });

        tool.executeResult(Map.of("query", "{ ping }"));
        assertThat(seen.get()).isEqualTo("{\"sub\":\"first\"}");

        Files.writeString(claimsFile, "{\"sub\":\"second\"}\n");
        tool.executeResult(Map.of("query", "{ ping }"));
        assertThat(seen.get()).isEqualTo("{\"sub\":\"second\"}");
    }

    @Test
    void missingClaimsFile_isAnErrorNamingTheProblem() {
        var tool = new ExecuteTool(config("@" + tempDir.resolve("absent.json"), false),
            (wiring, db, query, variables, contextArgs) -> "{}");
        var result = tool.executeResult(Map.of("query", "{ ping }"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("claims payload file");
    }

    @Test
    void perCallClaims_rejectedByDefaultWithAPointerAtTheOptIn() {
        var tool = new ExecuteTool(config("pinned", false),
            (wiring, db, query, variables, contextArgs) -> {
                throw new AssertionError("must not invoke");
            });
        var result = tool.executeResult(Map.of("query", "{ ping }", "claims", "other-identity"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("allowClaimsOverride");
    }

    @Test
    void perCallClaims_honoredWhenTheConfigOptsIn() {
        var seen = new AtomicReference<String>();
        var tool = new ExecuteTool(config("pinned", true),
            (wiring, db, query, variables, contextArgs) -> {
                seen.set(db.claims());
                return "{}";
            });
        var result = tool.executeResult(Map.of("query", "{ ping }", "claims", "other-identity"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(seen.get()).isEqualTo("other-identity");
    }

    @Test
    void invokerFailure_surfacesTheMessageVerbatimAsAnErrorResult() {
        // The hook is the validator and its errors are the feedback loop: the executor-side
        // message is the tool result.
        var tool = new ExecuteTool(config("pinned", false),
            (wiring, db, query, variables, contextArgs) -> {
                throw new DevQueryExecutor.DevExecutionException(
                    "connect hook rejected the payload: missing claim 'sub'");
            });
        var result = tool.executeResult(Map.of("query", "{ ping }"));

        assertThat(result.isError()).isTrue();
        assertThat(text(result)).isEqualTo("connect hook rejected the payload: missing claim 'sub'");
        assertThat(structured(result))
            .containsEntry("status", "error")
            .containsEntry("message", "connect hook rejected the payload: missing claim 'sub'");
    }

    @Test
    void unparseableResultJson_stillReturnsTheTextPayload() {
        // Structured content is best-effort; the text content is the contract.
        var tool = new ExecuteTool(config(null, false),
            (wiring, db, query, variables, contextArgs) -> "not-json");
        var result = tool.executeResult(Map.of("query", "{ ping }"));

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(text(result)).isEqualTo("not-json");
        assertThat(structured(result)).containsEntry("status", "ok");
    }
}
