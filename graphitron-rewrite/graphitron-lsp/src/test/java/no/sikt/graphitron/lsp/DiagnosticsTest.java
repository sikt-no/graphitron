package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Catalog-aware validation for known directives. Cleans-vs-typo test
 * matrix per directive plus the "no false positives on neutral schema"
 * sanity check.
 */
class DiagnosticsTest {

    @Test
    void unknownTableNameProducesError() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("MISSING").contains("table");
        assertThat(diags.get(0).getSeverity()).isEqualTo(DiagnosticSeverity.Error);
    }

    @Test
    void knownTableNameProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownColumnNameProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "TYPO")
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("TYPO").contains("column");
    }

    @Test
    void knownColumnNameProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownColumnButUnknownTableSuppressesDuplicateField() {
        // The @table is the typo, not the @field; reporting both would
        // double-count one mistake. The @field validator yields here.
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "anything")
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("MISSING");
    }

    @Test
    void unknownReferenceKeyProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "NOPE"}])
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("NOPE").contains("foreign key");
    }

    @Test
    void knownReferenceKeyProducesNoError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).isEmpty();
    }

    @Test
    void unknownReferenceTableProducesError() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "GHOST"}])
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).hasSize(1);
        assertThat(diags.get(0).getMessage()).contains("GHOST");
    }

    @Test
    void emptyArgumentValueProducesNoError() {
        // Mid-edit state: cursor sits in an empty quoted value. We
        // suggest completions but do not yelp at the empty string.
        var file = file("""
            type Foo @table(name: "") {
                bar: Int @field(name: "")
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        assertThat(diags).isEmpty();
    }

    @Test
    void diagnosticRangeCoversTheArgumentValueWithQuotes() {
        var file = file("""
            type Foo @table(name: "MISSING") {
                bar: Int
            }
            """);

        var diags = Diagnostics.compute(file, filmCatalog());

        var d = diags.get(0);
        // The reported range should sit on line 0 of the source.
        assertThat(d.getRange().getStart().getLine()).isZero();
        // Range covers the quoted token (start before opening quote, end after).
        assertThat(d.getRange().getStart().getCharacter())
            .isLessThan(d.getRange().getEnd().getCharacter());
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(
                new CompletionData.Column("film_id", "Integer", false, ""),
                new CompletionData.Column("title", "String", false, "")
            ),
            List.of(
                new CompletionData.Reference("language", "FILM__FILM_LANGUAGE_ID_FKEY", false)
            )
        );
        var language = new CompletionData.Table(
            "language", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
