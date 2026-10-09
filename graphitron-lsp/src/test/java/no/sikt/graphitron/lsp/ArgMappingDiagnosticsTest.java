package no.sikt.graphitron.lsp;

import no.sikt.graphitron.model.test.ClasspathCorpus;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
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

    /** One {@code compute} method whose one parameter is named {@code input}. */
    private static final String NAMED = "no.sikt.graphitron.rewrite.test.services.ComputingService";

    /**
     * The same method compiled without {@code -parameters}: one parameter, no name. The reading
     * records the absence rather than inventing {@code arg0}, which is what the left-side check
     * suppresses on.
     */
    private static final String NAMELESS = "no.sikt.graphitron.rewrite.test.nameless.NamelessService";

    @TempDir
    Path tmp;

    private List<Diagnostic> diagnose(String argMapping) {
        return diagnose(NAMED, argMapping);
    }

    private List<Diagnostic> diagnose(String className, String argMapping) {
        String source = "type Query { f(a: Int, input: Int): Int "
            + "@service(service: {className: \"" + className + "\", method: \"compute\", "
            + "argMapping: \"" + argMapping + "\"}) }\n";
        var file = WorkspaceFileTestSupport.snapshot(source);
        try (var store = StoreFixture.ofClasspath(tmp, List.of(ClasspathCorpus.service()))) {
            return Diagnostics.compute(BundledVocabulary.get(), "", file,
                Optional.of(store.handle()));
        }
    }

    @Test
    void validMappingProducesNoDiagnostics() {
        assertThat(diagnose("input: a")).isEmpty();
    }

    @Test
    void unknownJavaParameterFlagged() {
        var diags = diagnose("missing: a");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter 'missing'"));
    }

    @Test
    void unknownGraphqlArgumentFlagged() {
        var diags = diagnose("input: missing");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'missing'"));
    }

    @Test
    void duplicateJavaParameterFlagged() {
        var diags = diagnose("input: a, input: input");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Duplicate Java parameter 'input'"));
    }

    @Test
    void danglingColonFlagged() {
        var diags = diagnose("input:");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Missing GraphQL argument"));
    }

    @Test
    void strayCommaFlagged() {
        var diags = diagnose("input: a,");
        assertThat(diags).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Empty argMapping entry"));
    }

    @Test
    void unknownJavaParameterSuppressedWithoutParameterNames() {
        var diags = diagnose(NAMELESS, "missing: a");
        assertThat(diags).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown Java parameter"));
    }

    @Test
    void dotPathHeadSegmentValidatedAgainstFieldArguments() {
        // 'input' is a real field arg; the nested step 'missing' is not validated.
        assertThat(diagnose("input: input.missing")).noneSatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument"));
        // A typo'd head segment is flagged.
        assertThat(diagnose("input: missing.leaf")).anySatisfy(d ->
            assertThat(d.getMessage()).contains("Unknown GraphQL argument 'missing'"));
    }
}
