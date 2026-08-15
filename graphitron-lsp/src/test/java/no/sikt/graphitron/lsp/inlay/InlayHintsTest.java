package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LSP-tier unit tests for the inlay-hint renderers that still read the snapshot: given an authored
 * vs bare {@code @field} / {@code @reference} site and a fixed snapshot, only the bare site emits a
 * hint with the resolved value. Config gating and the empty cases live here too, because they are
 * properties of {@code compute} rather than of any one arm.
 *
 * <p>Stale-snapshot behaviour is exercised via {@link LspSchemaSnapshot.Built.Previous}
 * to confirm hints continue to render under the freshness-degraded arm.
 *
 * <p>Every call here passes an empty handle, which is how each case states that its renderer's
 * source is the snapshot. The arms that read the store instead are covered where a fixture can
 * capture one: {@code ClassificationHintsTest}, {@code SeparateFetchHintsTest} and, for the
 * {@code @table} half of this same inferred-directive arm, {@code InferredTableHintsTest}.
 */
class InlayHintsTest {

    @Test
    void noHintsWhenAllConfigOff() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(InlayHintConfig.defaults(), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void noSnapshotBackedHintsUnderUnavailableSnapshot() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        var hints = InlayHints.compute(
            new InlayHintConfig(true, true, false, false),
            file,
            Optional.empty(),
            LspSchemaSnapshot.unavailable(),
            fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void classificationHintsNeedTheStoreRatherThanTheSnapshot() {
        // The classification arm's toggle is on and the snapshot carries the projections the
        // incumbent arm read; with no store there is nothing to render, which is what makes the
        // arm's source the store and not the snapshot.
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(false, true, false, false), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void tableGhostsNeedTheStoreRatherThanTheSnapshot() {
        // Both @table passes moved onto the binding relation. The snapshot here carries exactly the
        // classification the incumbent renderer read, and with no store there is nothing to render.
        var file = file("""
            type Film @table {
                title: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of(),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false, false), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void inferredFieldHintRendersResolvedColumnName() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false, false), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"title\"");
    }

    @Test
    void inferredReferencePathHintRendersJoinChain() {
        var file = file("""
            type Film @table(name: "film") {
                languageName: String @reference
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Film.languageName", new FieldClassification.ColumnReference(
                "language", "languageName",
                List.of(new FieldClassification.FkStep("language", "film_language_id_fkey")))),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false, false), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .anySatisfy(label -> assertThat(label)
                .startsWith("path: [")
                .contains("key:")
                .contains("film_language_id_fkey"));
    }

    // ===== extend type X { ... } parity =====

    @Test
    void inferredFieldHintRendersInsideTypeExtension() {
        // extend type Customer where Customer is @table-classified by a definition in another
        // file: the inferred @field(name:) must resolve via the snapshot's name-keyed lookup
        // even though DeclarationKind.enclosing returns the extension node (which carries no
        // @table directive locally).
        var file = file("""
            extend type Customer {
                fullName: String @field
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Customer.fullName", new FieldClassification.Column("customer", "full_name")),
            Map.of("Customer", new TypeClassification.Table("customer"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false, false), file, Optional.empty(), snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"full_name\"");
    }

    @Test
    void hintsRenderUnderPreviousSnapshotForStaleness() {
        var file = file("""
            type Film @table(name: "film") {
                title: String @field
            }
            """);
        var previous = new LspSchemaSnapshot.Built.Previous(
            List.of(),
            Map.of(),
            Map.of(),
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, true, false, false), file, Optional.empty(), previous,
            fullRange(file));
        assertThat(hints).isNotEmpty();
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"title\"");
    }

    // ===== Test helpers =====

    private static LspSchemaSnapshot.Built snapshotWith(
        Map<String, FieldClassification> fields, Map<String, TypeClassification> types
    ) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of(),
            Map.of(),
            fields,
            types
        );
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange(FileSnapshot file) {
        // Generous full-document range so the visibility filter passes for every node.
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }
}
