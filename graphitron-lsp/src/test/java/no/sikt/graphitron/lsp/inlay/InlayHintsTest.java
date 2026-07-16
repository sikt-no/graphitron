package no.sikt.graphitron.lsp.inlay;

import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
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
 * LSP-tier unit tests for the inlay-hint provider. Two scopes:
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
        assertThat(labels).contains("Table", "Column");
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
        // Neither the present-but-bare arm (canonical arg present) nor the absent
        // arm (the directive node itself is present) should produce a @table hint.
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .doesNotContain("name: \"film\"")
            .noneMatch(label -> label.startsWith("@table"));
    }

    @Test
    void absentTableHintRendersOnObjectTypeWithoutDirective() {
        var file = file("""
            type Customer {
                name: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of(),
            Map.of("Customer", new TypeClassification.Table("customer"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("@table(name: \"customer\")");
    }

    @Test
    void absentTableHintRendersOnInputTypeWithoutDirective() {
        var file = file("""
            input ActorInput {
                name: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of(),
            Map.of("ActorInput", new TypeClassification.TableInput("actor"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("@table(name: \"actor\")");
    }

    @Test
    void absentTableHintSuppressedWhenDirectivePresent() {
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
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        // The present-but-bare arm renders "name: \"film\"" docked at the @table node;
        // the absent arm must not also render a full @table(...) hint on the type name.
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"film\"")
            .noneMatch(label -> label.startsWith("@table"));
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

    // ===== extend type X { ... } parity =====

    @Test
    void classificationHintsRenderOnTypeExtensionTypeName() {
        // extend type Query is the dominant root-organisation pattern; the classification label
        // must render on the extension's type-name token regardless of which file is open.
        var file = file("""
            extend type Query {
                allFilms: [Film!]
            }
            """);
        var snapshot = snapshotWith(
            Map.of("Query.allFilms", new FieldClassification.QueryTable("film", false)),
            Map.of("Query", new TypeClassification.Root("QUERY"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(false, true, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("Root", "QueryTable");
    }

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
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("name: \"full_name\"");
    }

    @Test
    void absentTableHintRendersOnTypeExtensionWithoutDirective() {
        // The absent-arm rides the broadened walk: extend type Customer whose definition
        // lives in another file (declared @table there) should still show the inferred
        // @table(...) ghost on the extension's type-name token.
        var file = file("""
            extend type Customer {
                fullName: String
            }
            """);
        var snapshot = snapshotWith(
            Map.of(),
            Map.of("Customer", new TypeClassification.Table("customer"))
        );
        var hints = InlayHints.compute(
            new InlayHintConfig(true, false, false), file, snapshot, fullRange(file));
        assertThat(hints).extracting(InlayHintsTest::labelOf)
            .contains("@table(name: \"customer\")");
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
            .contains("name: \"film\"", "Table", "Column");
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
