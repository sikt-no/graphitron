package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R160 — LSP-tier unit tests for the inlay-hint provider. Two scopes:
 * <ul>
 *   <li>The inferred-directive arm — given an authored vs bare {@code @table} /
 *       {@code @field} / {@code @reference} site and a fixed snapshot, asserts that
 *       only the bare site emits a hint with the resolved value.</li>
 *   <li>The classification arm — given a fixed snapshot, asserts that hints fire at
 *       the right declaration coordinates with the expected labels, and that config
 *       gating short-circuits when toggles are off.</li>
 * </ul>
 *
 * <p>Stale-snapshot behaviour is exercised via {@link LspSchemaSnapshot.Built.Previous}
 * to confirm hints continue to render under the freshness-degraded arm.
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
        var hints = InlayHints.compute(InlayHintConfig.defaults(), file, snapshot, fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void noHintsUnderUnavailableSnapshot() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var hints = InlayHints.compute(
            new InlayHintConfig(true, true, false),
            file,
            LspSchemaSnapshot.unavailable(),
            fullRange(file));
        assertThat(hints).isEmpty();
    }

    @Test
    void classificationHintsLabelFieldDeclarations() {
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
            new InlayHintConfig(false, true, false), file, snapshot, fullRange(file));
        var labels = hints.stream().map(h -> labelOf(h)).toList();
        assertThat(labels).contains("table type", "column");
    }

    @Test
    void inferredTableHintRendersResolvedName() {
        var file = file("""
            type Film @table {
                title: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of("Film", new TypeClassification.Table("film"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"film\"");
    }

    @Test
    void inferredTableHintSuppressedWhenAuthored() {
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
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .doesNotContain("name: \"film\"");
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
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
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
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .anySatisfy(label -> assertThat(label)
                .startsWith("path: [")
                .contains("key:")
                .contains("film_language_id_fkey"));
    }

    @Test
    void hintsRenderUnderPreviousSnapshotForStaleness() {
        var file = file("""
            type Film @table {
                title: String
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
            new InlayHintConfig(true, true, false), file, previous, fullRange(file));
        assertThat(hints).isNotEmpty();
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"film\"", "table type", "column");
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

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static Range fullRange(WorkspaceFile file) {
        // Generous full-document range so the visibility filter passes for every node.
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }
}
