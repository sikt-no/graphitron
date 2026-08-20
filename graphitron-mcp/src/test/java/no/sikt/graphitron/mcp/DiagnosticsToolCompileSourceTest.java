package no.sikt.graphitron.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.test.FactStores;
import no.sikt.graphitron.rewrite.compile.CompileDiagnostic;
import no.sikt.graphitron.rewrite.compile.CompileFacts;
import no.sikt.graphitron.rewrite.compile.CompileRound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static no.sikt.graphitron.rewrite.FactWriters.compileFacts;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code diagnostics} tool's compile-source arm: generated-code compile diagnostics surface
 * alongside the schema-validation entries, each tagged with a {@code source} discriminator
 * ({@code "compile"} vs {@code "schema"}). The rows arrive the production way: written into the
 * fact store by {@link CompileFacts} (the {@code javac_} family's shipped writer) and read back
 * through the {@code diagnostic} union view's compile arm, so this also exercises the view's
 * severity projection off javac's kind. Drives {@link DiagnosticsTool#diagnosticsResult}
 * directly (no live server needed) since the arm under test is the projection, not the
 * transport.
 */
class DiagnosticsToolCompileSourceTest {

    private static final String GRAPH = "DiagnosticsToolCompileSourceTest";

    /**
     * The path javac hands over, which is the form {@code javac_diagnostic.file} stores. Absolute,
     * as an emitted source's path is: the wire renders it as a URI, and a relative path would
     * render against whatever directory the test happens to run in.
     */
    private static final String GENERATED_ROOT = "/work/target/generated-sources/graphitron/gen/pkg";

    private static final CompileDiagnostic ERROR = new CompileDiagnostic(
        GENERATED_ROOT + "/FilmFetchers.java", 12, 7, "ERROR", "compiler.err.cant.resolve",
        "cannot find symbol");
    private static final CompileDiagnostic WARNING = new CompileDiagnostic(
        GENERATED_ROOT + "/Film.java", 3, 1, "WARNING", null, "deprecated API");

    @TempDir
    Path tmp;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> diagnostics(List<CompileDiagnostic> compile, Map<String, Object> args) {
        try (var store = FactStores.inMemory()) {
            compileFacts(store.dsl(), GRAPH, tmp)
                .write(new CompileRound(compile.stream().noneMatch(d -> "ERROR".equals(d.kind())), compile));
            McpSchema.CallToolResult result = DiagnosticsTool.diagnosticsResult(
                new StoreHandle(store.dsl(), GRAPH), args);
            var structured = (Map<String, Object>) result.structuredContent();
            return (List<Map<String, Object>>) structured.get("diagnostics");
        }
    }

    @Test
    void compileDiagnosticsCarrySourceCompileAndTheirGeneratedLocation() {
        var entries = diagnostics(List.of(ERROR, WARNING), Map.of());

        assertThat(entries).hasSize(2);
        var error = entries.stream().filter(e -> "error".equals(e.get("severity"))).findFirst().orElseThrow();
        assertThat(error.get("source")).isEqualTo("compile");
        assertThat(error.get("message")).isEqualTo("cannot find symbol");
        @SuppressWarnings("unchecked")
        var location = (Map<String, Object>) error.get("location");
        assertThat(location.get("uri"))
            .as("the store holds the path; the tool's location names a document by URI")
            .isEqualTo(SourceUri.of(GENERATED_ROOT + "/FilmFetchers.java"));
        // javac's 1-based line/column map to the 0-based wire shape the goto-definition consumers read.
        assertThat(location.get("line")).isEqualTo(11);
        assertThat(location.get("column")).isEqualTo(6);

        var warning = entries.stream().filter(e -> "warning".equals(e.get("severity"))).findFirst().orElseThrow();
        assertThat(warning.get("source")).isEqualTo("compile");
    }

    @Test
    void severityFilterAppliesToCompileDiagnostics() {
        var onlyErrors = diagnostics(List.of(ERROR, WARNING), Map.of("severity", "error"));

        assertThat(onlyErrors).hasSize(1);
        assertThat(onlyErrors.get(0).get("source")).isEqualTo("compile");
        assertThat(onlyErrors.get(0).get("severity")).isEqualTo("error");
    }

    @Test
    void coordinateFilterExcludesCompileDiagnostics() {
        // Compile diagnostics carry no schema coordinate; the null-safe coordinate filter matches
        // exactly the named coordinate, so the NULL-coordinate compile rows fall outside it.
        var entries = diagnostics(List.of(ERROR), Map.of("coordinate", "Film.title"));

        assertThat(entries).isEmpty();
    }
}
