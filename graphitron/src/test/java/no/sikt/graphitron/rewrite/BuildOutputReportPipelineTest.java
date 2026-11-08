package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.model.run.GraphitronStore;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.config.RunContext;
import no.sikt.graphitron.model.diagnostics.ValidationError;

/**
 * Producer-side wiring of {@link GraphQLRewriteGenerator#buildOutput()}.
 *
 * <p>The two edges of the report are already covered in isolation: {@code ValidationReportTest}
 * pins the {@link ValidationReport#from} factory, and the LSP-side {@code ValidatorDiagnosticsTest}
 * pins the consumer. This drives the actual producer end-to-end against the test jOOQ catalog and
 * asserts that <em>both</em> halves flow through {@code buildOutput()} onto
 * {@link GraphQLRewriteGenerator.BuildOutput#report()}: the store's refusals reach
 * {@code report().errors()}, and {@code bundle.model().warnings()} reaches
 * {@code report().warnings()} for a schema the store accepts. Without this, a refactor breaking the validator pass or the
 * warnings-into-report wiring leaves the build-time log surface correct (it still runs
 * {@link GraphQLRewriteGenerator#validate()}) while silently dropping diagnostics from the LSP's
 * report, with no other test gate — the failure mode identified when this test was originally deferred.
 */
@PipelineTier
class BuildOutputReportPipelineTest {

    /** A schema whose only diagnostic is the redundant @splitQuery under a record-backed parent. */
    private static final String WARNED = """
        type Language @table(name: "language") { name: String }
        type Film @table(name: "film") { title: String }
        type FilmDetails {
          language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }
        type Query {
          film: Film
          details: FilmDetails
            @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
        }
        """;

    @Test
    void buildOutput_populatesReportWithTheStoresRefusal(@TempDir Path tmp) throws IOException {
        // An unresolvable @reference key, which the store refuses before the walk runs.
        var report = report(tmp, WARNED.replace("type Film @table(name: \"film\") { title: String }",
            "type Film @table(name: \"film\") {\n  languageName: String @reference(path: [{key: \"no_such_fk\"}])\n}"));

        assertThat(report.errors())
            .as("the store's refusals flow through buildOutput() into the report")
            .isNotEmpty();
        assertThat(report.errors())
            .extracting(ValidationError::message)
            .as("the unresolvable @reference key surfaces as an error at its field")
            .anyMatch(m -> m.startsWith("Field 'Film.languageName':"));
        assertThat(report.isEmpty()).isFalse();
    }

    @Test
    void buildOutput_populatesReportWithModelWarnings(@TempDir Path tmp) throws IOException {
        var report = report(tmp, WARNED);

        assertThat(report.errors()).as("nothing in this schema is refused").isEmpty();
        assertThat(report.warnings())
            .as("model warnings flow through buildOutput() into the report")
            .extracting(BuildWarning::message)
            .as("the redundant @splitQuery directive surfaces as a build warning")
            .anyMatch(m -> m.contains("FilmDetails.language") && m.contains("redundant"));
    }

    private static ValidationReport report(Path tmp, String sdl) throws IOException {
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, sdl);
        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "BuildOutputReportPipelineTest",
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE
        );
        try (var store = GraphitronStore.captured(ctx)) {
            return new GraphQLRewriteGenerator(ctx, store).buildOutput().report();
        }
    }

    @Test
    void buildOutput_reportCarriesEngineLintFindingsWithTheirFix(@TempDir Path tmp) throws IOException {
        // The SDL lint engine's syntactic findings ride the same warning channel and must reach
        // the ValidationReport through buildOutput(), so the LSP and MCP project them for free. The
        // snake_case SDL field name trips field-names-camel-case; it maps to the real snake_case column
        // (the SDL name is what the rule flags, decoupled from the column), so the type still classifies.
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Film @table(name: "film") {
              original_language_id: Int
            }
            type Query { film: Film }
            """);

        var ctx = new RunContext(
            List.of(new SchemaInput(SchemaSource.file(schema), Optional.empty(), Optional.empty())),
            tmp, "BuildOutputReportPipelineTest", tmp, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        ValidationReport report;
        try (var store = GraphitronStore.captured(ctx)) {
            report = new GraphQLRewriteGenerator(ctx, store)
                .buildOutput().report();
        }

        var lintFinding = report.warnings().stream()
            .filter(w -> w instanceof BuildWarning.LintFinding lf
                && lf.rule() == LintRule.FIELD_NAMES_CAMEL_CASE)
            .map(BuildWarning.LintFinding.class::cast)
            .findFirst();

        assertThat(lintFinding)
            .as("an engine lint finding reaches the report through buildOutput()")
            .isPresent();
        assertThat(lintFinding.get().message()).contains("original_language_id");
        assertThat(lintFinding.get().fix())
            .as("the field-names-camel-case rename fix travels with the finding onto the report")
            .isPresent();
    }
}
