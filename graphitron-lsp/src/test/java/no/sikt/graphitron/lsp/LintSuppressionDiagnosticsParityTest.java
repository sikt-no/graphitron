package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.GraphQLRewriteGenerator;
import no.sikt.graphitron.rewrite.RewriteContext;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.lint.LintConfig;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import org.eclipse.lsp4j.Diagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R408 single-evaluator parity (LSP tier): a lint finding suppressed at the build does not replay as
 * an editor squiggle. This drives the real {@link GraphQLRewriteGenerator#buildOutput()} with a rule
 * disabled and feeds the resulting {@link ValidationReport}, the exact object the dev-loop hands to
 * the LSP through {@code Workspace.setBuildOutput}, into {@link Diagnostics#compute}. Because
 * suppression rides that single report rather than a Maven-log-only filter, the squiggle is gone at
 * the editor with no LSP-side filter; a co-present, non-disabled rule still surfaces.
 */
class LintSuppressionDiagnosticsParityTest {

    private static final String JOOQ_PACKAGE = "no.sikt.graphitron.rewrite.test.jooq";

    private static final String SDL = """
        type Film @table(name: "film") {
          original_language_id: Int
        }
        type Query { film: Film }
        """;

    @Test
    void buildSuppressedFindingDoesNotReplayAsSquiggle(@TempDir Path tmp) throws IOException {
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
    void withoutSuppression_theFindingSquiggles(@TempDir Path tmp) throws IOException {
        var diags = diagnosticsWith(tmp, LintConfig.empty());

        assertThat(diags)
            .as("control: without suppression the finding does squiggle")
            .anyMatch(d -> d.getMessage().contains("original_language_id"));
    }

    private static List<Diagnostic> diagnosticsWith(Path tmp, LintConfig lintConfig) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, SDL);
        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            tmp, tmp, "fake.output", JOOQ_PACKAGE, Map.of()
        ).withLintConfig(lintConfig);

        var output = new GraphQLRewriteGenerator(ctx).buildOutput();
        var uri = ValidationReport.canonicalUri(schema.toString());
        var file = new WorkspaceFile(1, SDL);
        return Diagnostics.compute(uri, file, output.artifacts().catalog(),
            output.artifacts().snapshot(), output.report());
    }
}
