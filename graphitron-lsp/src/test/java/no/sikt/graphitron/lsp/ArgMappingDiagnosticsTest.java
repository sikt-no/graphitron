package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * argMapping diagnostics: structural (empty entry, dangling colon), left-side
 * (unknown / duplicate Java parameter, suppressed without {@code -parameters}),
 * and right-side (unknown GraphQL argument, head segment only for dot-paths).
 */
class ArgMappingDiagnosticsTest {

    private static final String SERVICE_FQN = "com.example.PriceService";

    @TempDir
    Path tmp;

    /** One {@code compute} method whose parameters carry the given names. */
    private static List<CompletionData.ExternalReference> census(String... paramNames) {
        var params = new java.util.ArrayList<CompletionData.Parameter>();
        for (String n : paramNames) params.add(StoreFixture.parameter(n, "Object"));
        return List.of(StoreFixture.jarClass(SERVICE_FQN,
            List.of(StoreFixture.method("compute", "Object",
                params.toArray(CompletionData.Parameter[]::new)))));
    }

    /**
     * The same method compiled without {@code -parameters}: one parameter, no name. The census
     * records the absence rather than inventing {@code arg0}, which is what the left-side check
     * suppresses on.
     */
    private static List<CompletionData.ExternalReference> censusWithoutParameterNames() {
        return List.of(StoreFixture.jarClass(SERVICE_FQN,
            List.of(StoreFixture.method("compute", "Object",
                StoreFixture.parameter(null, "Object")))));
    }

    private List<Diagnostic> diagnose(
        List<CompletionData.ExternalReference> census, String argMapping
    ) {
        String source = "type Query { f(a: Int, input: Int): Int "
            + "@service(service: {className: \"com.example.PriceService\", method: \"compute\", "
            + "argMapping: \"" + argMapping + "\"}) }\n";
        var file = WorkspaceFileTestSupport.snapshot(source);
        try (var store = StoreFixture.ofClasspath(tmp, census)) {
            return Diagnostics.compute(LspVocabulary.load(), "", file,
                LspSchemaSnapshot.unavailable(), ValidationReport.empty(),
                Optional.of(store.handle()));
        }
    }

    @Test
    void validMappingProducesNoDiagnostics() {
        assertThat(diagnose(census("input"), "input: a")).isEmpty();
    }

    @Test
    void unknownJavaParameterFlagged() {
        var diags = diagnose(census("input"), "missing: a");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter 'missing'"));
    }

    @Test
    void unknownGraphqlArgumentFlagged() {
        var diags = diagnose(census("input"), "input: missing");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'missing'"));
    }

    @Test
    void duplicateJavaParameterFlagged() {
        var diags = diagnose(census("input"), "input: a, input: input");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Duplicate Java parameter 'input'"));
    }

    @Test
    void danglingColonFlagged() {
        var diags = diagnose(census("input"), "input:");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Missing GraphQL argument"));
    }

    @Test
    void strayCommaFlagged() {
        var diags = diagnose(census("input"), "input: a,");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Empty argMapping entry"));
    }

    @Test
    void unknownJavaParameterSuppressedWithoutParameterNames() {
        var diags = diagnose(censusWithoutParameterNames(), "missing: a");
        assertThat(diags).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter"));
    }

    @Test
    void dotPathHeadSegmentValidatedAgainstFieldArguments() {
        // 'input' is a real field arg; the nested step 'missing' is not validated.
        assertThat(diagnose(census("input"), "input: input.missing")).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument"));
        // A typo'd head segment is flagged.
        assertThat(diagnose(census("input"), "input: missing.leaf")).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'missing'"));
    }
}
