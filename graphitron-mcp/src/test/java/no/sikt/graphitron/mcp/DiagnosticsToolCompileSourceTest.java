package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R410 slice 6 — the {@code diagnostics} tool's compile-source arm: generated-code compile diagnostics
 * (off {@code Workspace.compileDiagnostics()}) surface alongside the schema-validation entries, each
 * tagged with a {@code source} discriminator ({@code "compile"} vs {@code "schema"}). This is the
 * agent-facing half of the spec's "a compile error reaches the console block and the MCP diagnostics
 * tool (with source: compile)" gate; the console half is pinned in {@code DevMojoTest}. Drives
 * {@link DiagnosticsTool#diagnosticsResult} directly (no live server needed) since the arm under test is
 * the projection, not the transport.
 */
class DiagnosticsToolCompileSourceTest {

    private static final CompileDiagnostic ERROR =
        new CompileDiagnostic("gen/pkg/FilmFetchers.java", 12, 7, "ERROR", "cannot find symbol");
    private static final CompileDiagnostic WARNING =
        new CompileDiagnostic("gen/pkg/Film.java", 3, 1, "WARNING", "deprecated API");

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> diagnostics(
        ValidationReport report, List<CompileDiagnostic> compile, Map<String, Object> args) {
        McpSchema.CallToolResult result = DiagnosticsTool.diagnosticsResult(
            report, compile, LspSchemaSnapshot.unavailable(), args);
        var structured = (Map<String, Object>) result.structuredContent();
        return (List<Map<String, Object>>) structured.get("diagnostics");
    }

    @Test
    void compileDiagnosticsCarrySourceCompileAndTheirGeneratedLocation() {
        var entries = diagnostics(ValidationReport.empty(), List.of(ERROR, WARNING), Map.of());

        assertThat(entries).hasSize(2);
        var error = entries.stream().filter(e -> "error".equals(e.get("severity"))).findFirst().orElseThrow();
        assertThat(error.get("source")).isEqualTo("compile");
        assertThat(error.get("message")).isEqualTo("cannot find symbol");
        @SuppressWarnings("unchecked")
        var location = (Map<String, Object>) error.get("location");
        assertThat(location.get("uri")).isEqualTo("gen/pkg/FilmFetchers.java");
        // javac's 1-based line/column map to the 0-based wire shape the goto-definition consumers read.
        assertThat(location.get("line")).isEqualTo(11);
        assertThat(location.get("column")).isEqualTo(6);

        var warning = entries.stream().filter(e -> "warning".equals(e.get("severity"))).findFirst().orElseThrow();
        assertThat(warning.get("source")).isEqualTo("compile");
    }

    @Test
    void severityFilterAppliesToCompileDiagnostics() {
        var onlyErrors = diagnostics(
            ValidationReport.empty(), List.of(ERROR, WARNING), Map.of("severity", "error"));

        assertThat(onlyErrors).hasSize(1);
        assertThat(onlyErrors.get(0).get("source")).isEqualTo("compile");
        assertThat(onlyErrors.get(0).get("severity")).isEqualTo("error");
    }

    @Test
    void coordinateFilterExcludesCompileDiagnostics() {
        // Compile diagnostics carry no schema coordinate, so a coordinate filter excludes them by
        // construction, exactly as it excludes schema warnings.
        var entries = diagnostics(
            ValidationReport.empty(), List.of(ERROR), Map.of("coordinate", "Film.title"));

        assertThat(entries).isEmpty();
    }
}
