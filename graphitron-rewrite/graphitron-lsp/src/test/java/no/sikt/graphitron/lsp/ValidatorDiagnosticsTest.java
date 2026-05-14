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
 * ({@link Rejection.AuthorError}, {@link Rejection.InvalidSchema}, {@link Rejection.Deferred});
 * the {@code RejectionSeverityCoverageTest} sibling pins exhaustiveness via reflection.
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
    void deferredMapsToWarningSeverity() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var error = new ValidationError(
            "Foo.bar",
            Rejection.deferred("variant not yet implemented", "some-roadmap-item"),
            new SourceLocation(5, 1, path));
        var report = ValidationReport.from(List.of(error), List.of());

        var diags = Diagnostics.compute(uri, file(), CompletionData.empty(), CURRENT_SNAPSHOT, report);

        assertThat(diags).singleElement().satisfies(d -> {
            assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
            assertThat(d.getSource()).isEqualTo("graphitron-validator");
        });
    }

    @Test
    void buildWarningMapsToWarningSeverity() {
        var path = "/tmp/schema.graphqls";
        var uri = ValidationReport.canonicalUri(path);
        var warning = new BuildWarning(
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

    private static WorkspaceFile file() {
        return new WorkspaceFile(1, "type Foo { x: Int }\n");
    }
}
