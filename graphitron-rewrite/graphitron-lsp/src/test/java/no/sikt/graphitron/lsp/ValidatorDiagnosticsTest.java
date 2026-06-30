package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.BuildWarning;
import no.sikt.graphitron.rewrite.ValidationError;
import no.sikt.graphitron.rewrite.ValidationReport;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.Rejection;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validator-error and warning surface for R147. The companion {@link DiagnosticsTest} covers the
 * pre-R147 SDL-only directive walks; this class covers the new validator slice that
 * {@link Diagnostics#validatorDiagnostics ...validatorDiagnostics} adds.
 *
 * <p>Severity mapping has one test per {@link Rejection} sealed permit
 * ({@link Rejection.AuthorError}, {@link Rejection.InvalidSchema}, {@link Rejection.Deferred}, all
 * three mapping to {@code Error} as of R225); the {@code RejectionSeverityCoverageTest} sibling
 * pins exhaustiveness via reflection.
 */
class ValidatorDiagnosticsTest {

    private static final LspSchemaSnapshot.Built.Current CURRENT_SNAPSHOT =
        new LspSchemaSnapshot.Built.Current(List.of(), Map.of(), Map.of());

    @Test
    void authorErrorMapsToErrorSeverityWithValidatorSource() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "Foo.bar",
            Rejection.structural("Field 'Foo.bar': lookup fields must not return a connection"),
            new SourceLocation(7, 3, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).hasSize(1);
        var d = diags.get(0);
        assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
        assertThat(d.getSource()).isEqualTo("graphitron-validator");
        assertThat(d.getMessage()).contains("lookup");
        assertThat(d.getRange().getStart().getLine()).isEqualTo(6);
        assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
        assertThat(d.getRange().getEnd().getLine()).isEqualTo(6);
        assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void invalidSchemaMapsToErrorSeverity() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "FooConnection.totalCount",
            Rejection.invalidSchema("Field 'FooConnection.totalCount' must be of type 'Int' (got 'String')"),
            new SourceLocation(3, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
            assertThat(d.getSource()).isEqualTo("graphitron-validator");
        });
    }

    @Test
    void deferredMapsToErrorSeverity() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "Foo.bar",
            Rejection.deferred("variant not yet implemented", "some-roadmap-item"),
            new SourceLocation(5, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
            assertThat(d.getSource()).isEqualTo("graphitron-validator");
        });
    }

    @Test
    void buildWarningMapsToWarningSeverity() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        BuildWarning warning = new BuildWarning.NoRule(
            "compiled without -parameters; parameter names unavailable",
            new SourceLocation(10, 5, path));
        var report = ValidationReport.from(List.of(), List.of(warning));

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
            assertThat(d.getSource()).isEqualTo("graphitron-validator");
            assertThat(d.getMessage()).contains("parameters");
        });
    }

    @Test
    void recordIgnoredBuildWarning_surfacesAsUsageSiteWarning() {
        // R307: the editor's "@record is ignored" signal is the generator's @record-ignored
        // BuildWarning, with no new LSP machinery. It rides this same validator-warning surface as
        // any BuildWarning (pipeline -> ValidationReport.warnings() -> validatorDiagnostics ->
        // usage-site Warning). The generator producing this warning for a reachable @record is
        // pinned by the generator module's RecordDirectiveIgnoredWarningTest; this pins that its
        // message lands at the usage site as a Warning. (The signal is reachability-gated: an
        // unreachable @record produces no warning and thus no editor signal, mirroring the generator.)
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        BuildWarning warning = BuildWarning.LintFinding.of(
            "Type 'FilmDetails' carries @record(record: { className: \"com.example.FilmDto\" }). "
            + "Graphitron derives the same backing class from the producing field's reflected return "
            + "type. The directive is redundant; remove it.",
            new SourceLocation(2, 18, path),
            no.sikt.graphitron.rewrite.lint.LintRule.REDUNDANT_RECORD_DIRECTIVE);
        var report = ValidationReport.from(List.of(), List.of(warning));

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
            assertThat(d.getSource()).isEqualTo("graphitron-validator");
            assertThat(d.getMessage()).contains("@record").contains("redundant");
        });
    }

    @Test
    void entriesForOtherFilesAreFiltered() {
        var openPath = "/tmp/open.graphqls";
        var openUri = ValidationReport.canonicalUri(openPath);
        var otherPath = "/tmp/other.graphqls";

        var ours = new ValidationError(
            "Foo.bar",
            Rejection.structural("error on the open file"),
            new SourceLocation(2, 1, openPath));
        var theirs = new ValidationError(
            "Baz.qux",
            Rejection.structural("error on a different file"),
            new SourceLocation(4, 1, otherPath));
        var report = ValidationReport.from(List.of(ours, theirs), List.of());

        var diags = Diagnostics.compute(openUri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d ->
            assertThat(d.getMessage()).isEqualTo("error on the open file"));
    }

    @Test
    void unavailableSnapshotSilencesValidatorDiagnostics() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "Foo.bar",
            Rejection.structural("error"),
            new SourceLocation(2, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(),
            LspSchemaSnapshot.unavailable(), report);

        assertThat(diags).isEmpty();
    }

    @Test
    void previousSnapshotSilencesValidatorDiagnostics() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "Foo.bar",
            Rejection.structural("error"),
            new SourceLocation(2, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(),
            new LspSchemaSnapshot.Built.Previous(List.of(), Map.of(), Map.of()), report);

        assertThat(diags).isEmpty();
    }

    @Test
    void nullLocationIsDroppedSilently() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var withLoc = new ValidationError(
            "Foo.bar", Rejection.structural("has location"),
            new SourceLocation(2, 1, path));
        var noLoc = new ValidationError(
            null, Rejection.structural("schema-wide"), null);
        var report = ValidationReport.from(List.of(withLoc, noLoc), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d ->
            assertThat(d.getMessage()).isEqualTo("has location"));
    }

    @Test
    void zeroLineLocationIsDroppedSilently() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var zero = new ValidationError(
            "Foo", Rejection.structural("programmatic attribution"),
            new SourceLocation(0, 0, path));
        var report = ValidationReport.from(List.of(zero), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).isEmpty();
    }

    @Test
    void emptyReportProducesNoValidatorDiagnostics() {
        var uri = ValidationReport.canonicalUri("/tmp/schema.graphqls");

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT,
            ValidationReport.empty());

        assertThat(diags).isEmpty();
    }

    /**
     * Wire-shape contract: after a report is replaced with an empty one, a subsequent
     * {@code compute()} call must return an empty {@link Diagnostic} list, which is the LSP
     * "clear all diagnostics for this URI" signal. The Workspace test pins the swap-and-recalculate
     * path; this test pins the per-call output.
     */
    @Test
    void emptyReportAfterErrorReportClearsDiagnostics() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var initial = ValidationReport.from(
            List.of(new ValidationError(
                "Foo.bar", Rejection.structural("error"),
                new SourceLocation(2, 1, path))),
            List.of());
        var first = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, initial);
        assertThat(first).hasSize(1);

        var second = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT,
            ValidationReport.empty());

        assertThat(second).isEmpty();
    }

    @Test
    void canonicalUriIsStable() {
        var path = "/tmp/schema.graphqls";
        assertThat(ValidationReport.canonicalUri(path))
            .isEqualTo(Path.of(path).toUri().toString());
    }

    /**
     * graphql-java anchors a described type's location at the opening {@code """} of its doc block.
     * The diagnostic must underline the type name, not the doc block. Own-line block form.
     */
    @Test
    void ownLineBlockDescription_reanchorsToTypeName() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var source = """
            ""\"
            A documented type.
            ""\"
            type Foo {
              bar: Int
            }
            """;
        // Type Foo's graphql-java location is the description start: line 1, col 1.
        var error = new ValidationError(
            "Foo", Rejection.structural("error on a documented type"),
            new SourceLocation(1, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(source), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        // "type Foo {" is line index 3; "Foo" spans characters 5..8.
        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getRange().getStart().getLine()).isEqualTo(3);
            assertThat(d.getRange().getStart().getCharacter()).isEqualTo(5);
            assertThat(d.getRange().getEnd().getLine()).isEqualTo(3);
            assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(8);
        });
    }

    /**
     * Inline block form {@code """..."""} — the case a content-newline heuristic over graphql-java's
     * description cannot place (it is indistinguishable from an own-line block) and the dominant
     * style in the directive schema. The tree-sitter walk lands on the field name exactly.
     */
    @Test
    void inlineBlockDescription_reanchorsToFieldName() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var source = """
            type Foo {
              ""\"An inline documented field.""\"
              bar: Int
            }
            """;
        // Field bar's graphql-java location is the inline description start: line 2, col 3.
        var error = new ValidationError(
            "Foo.bar", Rejection.structural("error on a documented field"),
            new SourceLocation(2, 3, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(source), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        // "  bar: Int" is line index 2; "bar" spans characters 2..5.
        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getRange().getStart().getLine()).isEqualTo(2);
            assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
            assertThat(d.getRange().getEnd().getLine()).isEqualTo(2);
            assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(5);
        });
    }

    /** Single-line {@code "..."} description form. */
    @Test
    void singleLineDescription_reanchorsToTypeName() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var source = """
            "A single-line documented type."
            type Foo {
              bar: Int
            }
            """;
        // Type Foo's graphql-java location is the description start: line 1, col 1.
        var error = new ValidationError(
            "Foo", Rejection.structural("error on a documented type"),
            new SourceLocation(1, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(source), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        // "type Foo {" is line index 1; "Foo" spans characters 5..8.
        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
            assertThat(d.getRange().getStart().getCharacter()).isEqualTo(5);
            assertThat(d.getRange().getEnd().getLine()).isEqualTo(1);
            assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(8);
        });
    }

    /**
     * No description: graphql-java already reports the declaration line, so the diagnostic keeps the
     * column-to-end-of-line range straight from the location. Pins that the re-anchor is inert
     * outside doc blocks.
     */
    @Test
    void noDescription_keepsColumnToEndOfLineRange() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var source = """
            type Foo {
              bar: Int
            }
            """;
        // Field bar with no doc block: graphql-java reports the name line: line 2, col 3.
        var error = new ValidationError(
            "Foo.bar", Rejection.structural("error on an undocumented field"),
            new SourceLocation(2, 3, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(source), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
            assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
            assertThat(d.getRange().getEnd().getLine()).isEqualTo(1);
            assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(Integer.MAX_VALUE);
        });
    }

    private static WorkspaceFile file() {
        return new WorkspaceFile(1, "type Foo { x: Int }\n");
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }
}
