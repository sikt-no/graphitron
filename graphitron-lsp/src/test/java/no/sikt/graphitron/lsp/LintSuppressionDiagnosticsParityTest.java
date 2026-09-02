package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.model.lint.LintConfig;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Single-evaluator parity (LSP tier): a lint finding suppressed at the build does not replay as
 * an editor squiggle. This drives the real {@link GraphQLRewriteGenerator#buildOutput()} with a rule
 * disabled, writes its warnings through the loader a dev round writes them with, and replays them
 * the way the editor does. Because suppression is applied before the loader's input rather than by a
 * Maven-log-only filter, the squiggle is gone at the editor with no LSP-side filter; a co-present,
 * non-disabled rule still surfaces.
 */
class LintSuppressionDiagnosticsParityTest {

    private static final String SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
        }
        type Query { film: Film }
        """;

    @Test
    void buildSuppressedFindingDoesNotReplayAsSquiggle(@TempDir Path tmp) {
        var diags = diagnosticsWith(tmp,
            LintConfig.validated(Set.of("field-names-camel-case"), List.of()));

        assertThat(diags)
            .as("the build-suppressed field-names-camel-case finding does not squiggle in the editor")
            .noneMatch(d -> d.getMessage().contains("original_language_id"));
        assertThat(diags)
            .as("a non-disabled rule still surfaces, so suppression is selective, not a blanket mute")
            .anyMatch(d -> d.getMessage().contains("should have a description"));
    }

    @Test
    void withoutSuppression_theFindingSquiggles(@TempDir Path tmp) {
        var diags = diagnosticsWith(tmp, LintConfig.empty());

        assertThat(diags)
            .as("control: without suppression the finding does squiggle")
            .anyMatch(d -> d.getMessage().contains("original_language_id"));
    }

    private static List<Diagnostic> diagnosticsWith(Path tmp, LintConfig lintConfig) {
        // The dev round's own sequence: a real build, its findings loaded into the store, and the
        // editor reading them back from there rather than from a report object.
        try (var fixture = StoreFixture.ofBuild(tmp, SDL, lintConfig)) {
            return Diagnostics.compute(BundledVocabulary.get(),
                SourceUri.of(fixture.sourceName()),
                WorkspaceFileTestSupport.snapshot(SDL), Optional.of(fixture.handle()));
        }
    }
}
