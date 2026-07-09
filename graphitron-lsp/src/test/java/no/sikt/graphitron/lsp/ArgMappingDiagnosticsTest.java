package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * argMapping diagnostics: structural (empty entry, dangling colon), left-side
 * (unknown / duplicate Java parameter, suppressed without {@code -parameters}),
 * and right-side (unknown GraphQL argument, head segment only for dot-paths).
 */
class ArgMappingDiagnosticsTest {

    private static CompletionData catalog(String... paramNames) {
        var params = new java.util.ArrayList<CompletionData.Parameter>();
        for (String n : paramNames) params.add(new CompletionData.Parameter(n, "Object", null, ""));
        var method = new CompletionData.Method("compute", "Object", "", List.copyOf(params));
        return new CompletionData(List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.PriceService", "com.example.PriceService", "",
                List.of(method), List.of())));
    }

    private static CompletionData catalogNullParam() {
        var method = new CompletionData.Method("compute", "Object", "",
            List.of(new CompletionData.Parameter(null, "Object", null, "")));
        return new CompletionData(List.of(), List.of(),
            List.of(new CompletionData.ExternalReference(
                "com.example.PriceService", "com.example.PriceService", "",
                List.of(method), List.of())));
    }

    private static List<Diagnostic> diagnose(CompletionData data, String argMapping) {
        String source = "type Query { f(a: Int, input: Int): Int "
            + "@service(service: {className: \"com.example.PriceService\", method: \"compute\", "
            + "argMapping: \"" + argMapping + "\"}) }\n";
        var file = WorkspaceFileTestSupport.snapshot(source);
        return Diagnostics.compute("", file, data, LspSchemaSnapshot.unavailable(), ValidationReport.empty());
    }

    @Test
    void validMappingProducesNoDiagnostics() {
        assertThat(diagnose(catalog("input"), "input: a")).isEmpty();
    }

    @Test
    void unknownJavaParameterFlagged() {
        var diags = diagnose(catalog("input"), "ghost: a");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter 'ghost'"));
    }

    @Test
    void unknownGraphqlArgumentFlagged() {
        var diags = diagnose(catalog("input"), "input: ghost");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'ghost'"));
    }

    @Test
    void duplicateJavaParameterFlagged() {
        var diags = diagnose(catalog("input"), "input: a, input: input");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Duplicate Java parameter 'input'"));
    }

    @Test
    void danglingColonFlagged() {
        var diags = diagnose(catalog("input"), "input:");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Missing GraphQL argument"));
    }

    @Test
    void strayCommaFlagged() {
        var diags = diagnose(catalog("input"), "input: a,");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Empty argMapping entry"));
    }

    @Test
    void unknownJavaParameterSuppressedWithoutParameterNames() {
        var diags = diagnose(catalogNullParam(), "ghost: a");
        assertThat(diags).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter"));
    }

    @Test
    void dotPathHeadSegmentValidatedAgainstFieldArguments() {
        // 'input' is a real field arg; the nested step 'missing' is not validated.
        assertThat(diagnose(catalog("input"), "input: input.missing")).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument"));
        // A typo'd head segment is flagged.
        assertThat(diagnose(catalog("input"), "input: ghost.missing")).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'ghost'"));
    }
}
