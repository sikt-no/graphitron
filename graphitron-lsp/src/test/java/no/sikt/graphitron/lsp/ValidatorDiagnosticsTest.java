package no.sikt.graphitron.lsp;

import graphql.language.SourceLocation;
import no.sikt.graphitron.lsp.diagnostics.Diagnostics;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.model.read.SourceUri;
import no.sikt.graphitron.model.diagnostics.BuildWarning;
import no.sikt.graphitron.model.diagnostics.ValidationError;
import no.sikt.graphitron.model.lint.LintRule;
import no.sikt.graphitron.model.diagnostics.Rejection;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The replay of what a build said about a document. The companion {@link DiagnosticsTest} covers the
 * verdicts the walk reaches about the buffer itself; every case here is about a finding some earlier
 * build reached, written to the store's diagnostics stratum by the loaders a dev round runs and read
 * back through the {@code diagnostic} view.
 *
 * <p>Every fixture writes through the real loaders, so a case cannot pin a row shape a build does not
 * produce, and severity is the view's rather than a switch this module owns: the rejection arms are
 * errors by the build's own finality, the lint and advisory arms warnings by construction.
 */
class ValidatorDiagnosticsTest {

    @TempDir
    Path tmp;

    private static final String SDL = "type Foo { x: Int }\n";

    @Test
    void rejectionReplaysAsAnErrorAtItsLocation() {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withValidationErrors(List.of(error(fixture, 7, 3,
                Rejection.structural("Field 'Foo.bar': lookup fields must not return a connection"))));

            var diags = replay(fixture, file(SDL));

            assertThat(diags).singleElement().satisfies(d -> {
                assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
                assertThat(d.getSource()).isEqualTo("graphitron-validator");
                assertThat(d.getMessage()).contains("lookup");
                assertThat(d.getRange().getStart().getLine()).isEqualTo(6);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
                assertThat(d.getRange().getEnd().getLine()).isEqualTo(6);
                assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(Integer.MAX_VALUE);
            });
        }
    }

    @Test
    void aDeferredRejectionReplaysAsAnErrorToo() {
        // Every rejection arm fails the build, the deferred one included, so the editor shows the same
        // finality the console does. What an author acts on is the message, not a softer colour.
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withValidationErrors(List.of(error(fixture, 5, 1,
                Rejection.deferred("variant not yet implemented"))));

            assertThat(replay(fixture, file(SDL))).singleElement().satisfies(d ->
                assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error));
        }
    }

    @Test
    void anAdvisoryWarningReplaysAsAWarning() {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withBuildWarnings(List.of(new BuildWarning.NoRule(
                "compiled without -parameters; parameter names unavailable",
                new SourceLocation(10, 5, fixture.sourceName()))));

            assertThat(replay(fixture, file(SDL))).singleElement().satisfies(d -> {
                assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
                assertThat(d.getSource()).isEqualTo("graphitron-validator");
                assertThat(d.getMessage()).contains("parameters");
            });
        }
    }

    @Test
    void aLintFindingReplaysAsAWarningAtItsOwnRange() {
        // Parity for free: an engine lint rule's finding rides the same stratum as a rejection and
        // replays into a Warning squiggle at its own range, with no second evaluator in the editor.
        var source = """
            type Widget {
              created_at: String
            }
            """;
        try (var fixture = StoreFixture.of(tmp, source)) {
            fixture.withBuildWarnings(List.of(BuildWarning.LintFinding.of(
                "Field name 'created_at' should be camelCase.",
                new SourceLocation(2, 3, fixture.sourceName()),
                LintRule.FIELD_NAMES_CAMEL_CASE)));

            assertThat(replay(fixture, file(source))).singleElement().satisfies(d -> {
                assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Warning);
                assertThat(d.getMessage()).contains("camelCase");
                assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
            });
        }
    }

    @Test
    void aRefusedParseReplaysTheParsersOwnVerdict() {
        // The behaviour this slice recovers. A source the parser refused has no build output to
        // replay, so the incumbent showed the author nothing at all in the file they had just broken;
        // the SDL toolchain writes its refusal like any other verdict, and it replays like one.
        var broken = "type Foo { x: }\n";
        try (var fixture = StoreFixture.ofRefusedSchema(tmp, broken)) {
            assertThat(replay(fixture, file(broken))).singleElement().satisfies(d -> {
                assertThat(d.getSeverity()).isEqualTo(DiagnosticSeverity.Error);
                assertThat(d.getSource()).isEqualTo("graphitron-validator");
            });
        }
    }

    @Test
    void findingsAboutOtherFilesAreNotReplayedHere() {
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withValidationErrors(List.of(
                error(fixture, 2, 1, Rejection.structural("error on the open file")),
                new ValidationError("Baz.qux", Rejection.structural("error on a different file"),
                    new SourceLocation(4, 1, tmp.resolve("other.graphqls").toString()))));

            assertThat(replay(fixture, file(SDL))).singleElement().satisfies(d ->
                assertThat(d.getMessage()).isEqualTo("error on the open file"));
        }
    }

    @Test
    void anUnlocatedFindingIsNotReplayed() {
        // A finding the build could not place is real and is the console's to report; there is no
        // span in a buffer to squiggle for it, so the row carries no line and the replay skips it.
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withValidationErrors(List.of(
                error(fixture, 2, 1, Rejection.structural("has location")),
                new ValidationError(null, Rejection.structural("schema-wide"), null)));

            assertThat(replay(fixture, file(SDL))).singleElement().satisfies(d ->
                assertThat(d.getMessage()).isEqualTo("has location"));
        }
    }

    @Test
    void aRoundThatFoundNothingClearsWhatTheLastOneFound() {
        // Wire-shape contract: an empty list is the LSP "clear all diagnostics for this URI" signal,
        // and it follows from the loader replacing the graph's partition rather than adding to it.
        try (var fixture = StoreFixture.of(tmp, SDL)) {
            fixture.withValidationErrors(List.of(error(fixture, 2, 1, Rejection.structural("error"))));
            assertThat(replay(fixture, file(SDL))).hasSize(1);

            fixture.withValidationErrors(List.of());

            assertThat(replay(fixture, file(SDL))).isEmpty();
        }
    }

    @Test
    void withNoStoreNothingIsReplayed() {
        // A session before its first build, which is also a bare launcher outside one: no store, so
        // no verdict to replay and nothing to show for one.
        assertThat(Diagnostics.compute(BundledVocabulary.get(), "file:///tmp/schema.graphqls", file(SDL)))
            .isEmpty();
    }

    /**
     * graphql-java anchors a described definition's location at the opening delimiter of its doc
     * block. The squiggle must land on the name, not the prose. Own-line block form.
     */
    @Test
    void ownLineBlockDescription_reanchorsToTypeName() {
        var source = """
            ""\"
            A documented type.
            ""\"
            type Foo {
              bar: Int
            }
            """;
        try (var fixture = StoreFixture.of(tmp, source)) {
            // Type Foo's graphql-java location is the description start: line 1, col 1.
            fixture.withValidationErrors(List.of(error(fixture, 1, 1,
                Rejection.structural("error on a documented type"))));

            // "type Foo {" is line index 3; "Foo" spans characters 5..8.
            assertThat(replay(fixture, file(source))).singleElement().satisfies(d -> {
                assertThat(d.getRange().getStart().getLine()).isEqualTo(3);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(5);
                assertThat(d.getRange().getEnd().getLine()).isEqualTo(3);
                assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(8);
            });
        }
    }

    /**
     * Inline block form {@code """..."""}, the case a content-newline heuristic over graphql-java's
     * description cannot place (it is indistinguishable from an own-line block) and the dominant
     * style in the directive schema. The walk's collected descriptions land on the field name exactly.
     */
    @Test
    void inlineBlockDescription_reanchorsToFieldName() {
        var source = """
            type Foo {
              ""\"An inline documented field.""\"
              bar: Int
            }
            """;
        try (var fixture = StoreFixture.of(tmp, source)) {
            // Field bar's graphql-java location is the inline description start: line 2, col 3.
            fixture.withValidationErrors(List.of(error(fixture, 2, 3,
                Rejection.structural("error on a documented field"))));

            // "  bar: Int" is line index 2; "bar" spans characters 2..5.
            assertThat(replay(fixture, file(source))).singleElement().satisfies(d -> {
                assertThat(d.getRange().getStart().getLine()).isEqualTo(2);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
                assertThat(d.getRange().getEnd().getLine()).isEqualTo(2);
                assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(5);
            });
        }
    }

    /** Single-line {@code "..."} description form. */
    @Test
    void singleLineDescription_reanchorsToTypeName() {
        var source = """
            "A single-line documented type."
            type Foo {
              bar: Int
            }
            """;
        try (var fixture = StoreFixture.of(tmp, source)) {
            // Type Foo's graphql-java location is the description start: line 1, col 1.
            fixture.withValidationErrors(List.of(error(fixture, 1, 1,
                Rejection.structural("error on a documented type"))));

            // "type Foo {" is line index 1; "Foo" spans characters 5..8.
            assertThat(replay(fixture, file(source))).singleElement().satisfies(d -> {
                assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(5);
                assertThat(d.getRange().getEnd().getLine()).isEqualTo(1);
                assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(8);
            });
        }
    }

    /**
     * No description: graphql-java already reports the declaration line, so the squiggle keeps the
     * column-to-end-of-line range straight from the stored location. Pins that the re-anchor is inert
     * outside doc blocks.
     */
    @Test
    void noDescription_keepsColumnToEndOfLineRange() {
        var source = """
            type Foo {
              bar: Int
            }
            """;
        try (var fixture = StoreFixture.of(tmp, source)) {
            // Field bar with no doc block: graphql-java reports the name line: line 2, col 3.
            fixture.withValidationErrors(List.of(error(fixture, 2, 3,
                Rejection.structural("error on an undocumented field"))));

            assertThat(replay(fixture, file(source))).singleElement().satisfies(d -> {
                assertThat(d.getRange().getStart().getLine()).isEqualTo(1);
                assertThat(d.getRange().getStart().getCharacter()).isEqualTo(2);
                assertThat(d.getRange().getEnd().getLine()).isEqualTo(1);
                assertThat(d.getRange().getEnd().getCharacter()).isEqualTo(Integer.MAX_VALUE);
            });
        }
    }

    /** The buffer under test, opened at the URI the fixture's own schema file has. */
    private static List<Diagnostic> replay(StoreFixture fixture, FileSnapshot file) {
        return Diagnostics.compute(BundledVocabulary.get(),
            SourceUri.of(fixture.sourceName()), file, Optional.of(fixture.handle()));
    }

    /** One walk error at a location in the fixture's own schema file. */
    private static ValidationError error(
        StoreFixture fixture, int line, int column, Rejection rejection
    ) {
        return new ValidationError("Foo.bar", rejection,
            new SourceLocation(line, column, fixture.sourceName()));
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
