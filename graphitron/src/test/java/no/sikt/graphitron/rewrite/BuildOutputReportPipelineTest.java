package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.lint.LintRule;
import no.sikt.graphitron.rewrite.schema.input.SchemaInput;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R149 (bullet 2): producer-side wiring of {@link GraphQLRewriteGenerator#buildOutput()}.
 *
 * <p>The two edges of the report are already covered in isolation: {@code ValidationReportTest}
 * pins the {@link ValidationReport#from} factory, and the LSP-side {@code ValidatorDiagnosticsTest}
 * pins the consumer. This drives the actual producer end-to-end against the test jOOQ catalog and
 * asserts that <em>both</em> halves flow through {@code buildOutput()} onto
 * {@link GraphQLRewriteGenerator.BuildOutput#report()}: the validator pass over {@code bundle.model()}
 * reaches {@code report().errors()}, and {@code bundle.model().warnings()} reaches
 * {@code report().warnings()}. Without this, a refactor breaking the validator pass or the
 * warnings-into-report wiring leaves the build-time log surface correct (it still runs
 * {@link GraphQLRewriteGenerator#validate()}) while silently dropping diagnostics from the LSP's
 * report, with no other test gate, the failure mode R147 named when it deferred this test.
 */
@PipelineTier
class BuildOutputReportPipelineTest {

    @Test
    void buildOutput_populatesReportWithValidatorErrorsAndModelWarnings(@TempDir Path tmp) throws IOException {
        // One schema, two independent diagnostics on two different types:
        //  - Film.languageName: @reference to a non-existent FK -> UnclassifiedField -> validator error.
        //  - FilmDetails.language: @splitQuery on a record-backed parent (FilmDetails binds to the
        //    @service producer's reflected return) -> "@splitQuery is redundant" build warning.
        // The warning half no longer leans on a redundant @record (the directive is ignored and
        // its warning coverage lives in RecordDirectiveIgnoredWarningTest); any model BuildWarning
        // exercises the same wiring, so this uses the @splitQuery-redundant warning instead.
        // The two are independent so each half of the report is exercised in the same run.
        Path schema = tmp.resolve("schema.graphqls");
        Files.writeString(schema, """
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
              languageName: String @reference(path: [{key: "no_such_fk"}])
            }
            type FilmDetails {
              language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query {
              film: Film
              details: FilmDetails
                @service(service: {className: "no.sikt.graphitron.codereferences.dummyreferences.DummyService", method: "makeDummyRecord"})
            }
            """);

        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            tmp,
            tmp,
            DEFAULT_OUTPUT_PACKAGE,
            DEFAULT_JOOQ_PACKAGE,
            Map.of()
        );

        var report = new GraphQLRewriteGenerator(ctx).buildOutput().report();

        // Error half: the validator pass over bundle.model() reaches report.errors().
        assertThat(report.errors())
            .as("validator errors flow through buildOutput() into the report")
            .isNotEmpty();
        assertThat(report.errors())
            .extracting(ValidationError::message)
            .as("the unresolvable @reference key surfaces as a validator error")
            .anyMatch(m -> m.contains("languageName") || m.contains("no_such_fk") || m.contains("did you mean:"));

        // Warning half: bundle.model().warnings() reaches report.warnings().
        assertThat(report.warnings())
            .as("model warnings flow through buildOutput() into the report")
            .isNotEmpty();
        assertThat(report.warnings())
            .extracting(BuildWarning::message)
            .as("the redundant @splitQuery directive surfaces as a build warning")
            .anyMatch(m -> m.contains("FilmDetails.language") && m.contains("redundant"));

        assertThat(report.isEmpty()).isFalse();
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

        var ctx = new RewriteContext(
            List.of(new SchemaInput(schema.toString(), Optional.empty(), Optional.empty())),
            tmp, tmp, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE, Map.of());

        var report = new GraphQLRewriteGenerator(ctx).buildOutput().report();

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
