package no.sikt.graphitron.lsp.hover;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R160 — LSP-tier unit tests for the classification-hover arm. Asserts that cursor
 * on a field-definition or type-definition name token resolves through
 * {@link DeclarationHovers#compute} into a hover whose markdown carries the projection
 * payload.
 */
class DeclarationHoversTest {

    @Test
    void cursorOnTypeNameProducesTypeClassificationHover() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        // Cursor on the 'F' of 'Film'.
        var pos = pointAt(file, 0, "type Film".length() - 4);

        var snapshot = snapshotWith(
            Map.of(),
            Map.of("Film", new TypeClassification.Table("film")));
        var hover = DeclarationHovers.compute(file, snapshot, pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md)
            .contains("**table type**")
            .contains("Table: `film`");
    }

    @Test
    void cursorOnFieldNameProducesFieldClassificationHover() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        // Cursor inside 'title' on line 1.
        var pos = pointAt(file, 1, "    titl".length());

        var snapshot = snapshotWith(
            Map.of("Film.title", new FieldClassification.Column("film", "title")),
            Map.of());
        var hover = DeclarationHovers.compute(file, snapshot, pos).orElseThrow();

        var md = hover.getContents().getRight().getValue();
        assertThat(md)
            .contains("**column**")
            .contains("`Film.title`")
            .contains("Column `title` on `film`");
    }

    @Test
    void cursorOnDirectiveArgReturnsEmpty() {
        // Cursor inside the @table(name: ...) — DeclarationHovers should not fire.
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var pos = pointAt(file, 0, "type Film @table(na".length());

        var snapshot = snapshotWith(
            Map.of(),
            Map.of("Film", new TypeClassification.Table("film")));
        assertThat(DeclarationHovers.compute(file, snapshot, pos)).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var pos = pointAt(file, 0, "type Fi".length());
        assertThat(DeclarationHovers.compute(file, LspSchemaSnapshot.unavailable(), pos)).isEmpty();
    }

    @Test
    void missingProjectionReturnsEmpty() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var pos = pointAt(file, 0, "type Fi".length());

        var snapshot = snapshotWith(Map.of(), Map.of());
        assertThat(DeclarationHovers.compute(file, snapshot, pos)).isEmpty();
    }

    @Test
    void dmlMutationFieldRendersKindTableAndInputType() {
        var file = file("""
            type Mutation {
                addActor(input: ActorInput!): Actor @mutation(typeName: INSERT)
            }
            """);
        var pos = pointAt(file, 1, "    addAc".length());

        var snapshot = snapshotWith(
            Map.of("Mutation.addActor", new FieldClassification.DmlMutation(
                "actor", "ActorInput",
                no.sikt.graphitron.rewrite.model.DmlKind.INSERT, "ACTOR_PAYLOAD")),
            Map.of());
        var hover = DeclarationHovers.compute(file, snapshot, pos).orElseThrow();
        var md = hover.getContents().getRight().getValue();
        assertThat(md)
            .contains("**insert mutation**")
            .contains("Kind: INSERT")
            .contains("Table: `actor`")
            .contains("Input type: `ActorInput`")
            .contains("Error channel: `ACTOR_PAYLOAD`");
    }

    // ===== Helpers =====

    private static LspSchemaSnapshot.Built snapshotWith(
        Map<String, FieldClassification> fields, Map<String, TypeClassification> types
    ) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(), fields, types);
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static Point pointAt(WorkspaceFile file, int line, int col) {
        return new Point(line, col);
    }
}
