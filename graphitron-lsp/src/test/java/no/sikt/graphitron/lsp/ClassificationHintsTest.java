package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.InlayHint;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inlay-hint provider's classification arm, over a real capture. The label is the classifier
 * the claim stratum carries and nothing more: which table, which column and which join path are
 * the hover's business, read from the relations that own them, so a compact annotation cannot fall
 * behind a taxonomy it would otherwise have to restate.
 *
 * <p>Every assertion runs under an unavailable snapshot, which is how the class states that this
 * arm's source is the store. The sibling {@code InlayHintsTest} holds the snapshot-reading arm and
 * passes an empty handle for the same reason.
 */
class ClassificationHintsTest {

    /**
     * A schema whose coordinates cover every shape the arm answers: a claimed type, a field the
     * structural classifier reaches, a field an authored claim masks it at, a coordinate two
     * claims land on, and declarations nothing claims at all.
     */
    private static final String SDL = """
        type Query { placeholder: Int }

        type Film @table(name: "film") {
            title: String
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
        }

        type Note {
            text: String
        }

        type FilmError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
            message: String
        }
        """;

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL);
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aClaimedTypeAndItsColumnMatchedFieldAreLabelledWithTheirClassifiers() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("TABLE", "TABLE_COLUMN");
    }

    @Test
    void anAuthoredClaimIsTheLabelWhereItMasksTheStructuralReading() {
        // `rating` is a column of `film`, so the structural classifier reaches this coordinate;
        // the label is SERVICE because the reduction masks it, not because the arm ranks the two.
        var file = file("""
            type Film @table(name: "film") {
                rating: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("TABLE", "SERVICE");
    }

    @Test
    void twoClaimsAtOneCoordinateAreBothTheLabel() {
        // The conflict rendered as what it is. A single word naming the fact that a coordinate was
        // claimed twice would say strictly less than the two classifiers that claimed it.
        var file = file("""
            type Film @table(name: "film") {
                filmId: ID
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("TABLE", "NODE_ID, SERVICE");
    }

    @Test
    void anErrorTypeCarriesItsOwnClassifier() {
        var file = file("""
            type FilmError {
                message: String
            }
            """);
        // The type is claimed; its field is not, no table being bound to read a column off.
        assertThat(labels(file, fullRange())).containsExactly("ERROR");
    }

    @Test
    void aDeclarationNoClassifierClaimsGetsNoLabel() {
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(labels(file, fullRange())).isEmpty();
    }

    @Test
    void aRootTypeIsNotClaimedByTheTypeGrain() {
        // A root classifies before any type directive is read, which is why the claim view masks
        // the three root names out; the arm inherits that rather than restating it.
        var file = file("""
            type Query { placeholder: Int }
            """);
        assertThat(labels(file, fullRange())).isEmpty();
    }

    @Test
    void onlyDeclarationsInTheVisibleRangeAreQueried() {
        var file = file("""
            type Note {
                text: String
            }
            type Film @table(name: "film") {
                title: String
            }
            """);
        // A range holding the second declaration only: the arm collects its sites before asking
        // the store anything, so the first type is not merely unrendered but never asked about.
        var visible = new Range(new Position(3, 0), new Position(5, 1));
        assertThat(labels(file, visible)).containsExactly("TABLE", "TABLE_COLUMN");
    }

    @Test
    void noLabelsWithoutAStore() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        var hints = InlayHints.compute(config(), file, Optional.empty(),
            LspSchemaSnapshot.unavailable(), fullRange());
        assertThat(hints).isEmpty();
    }

    // ===== Test helpers =====

    /** The classification arm alone: the inferred-directive arm has a test class of its own. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(false, true, false);
    }

    private static List<String> labels(FileSnapshot file, Range visible) {
        return InlayHints.compute(config(), file, Optional.of(store.handle()),
                LspSchemaSnapshot.unavailable(), visible)
            .stream()
            .map(hint -> {
                var either = hint.getLabel();
                return either.isLeft() ? either.getLeft() : either.getRight().toString();
            })
            .toList();
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
