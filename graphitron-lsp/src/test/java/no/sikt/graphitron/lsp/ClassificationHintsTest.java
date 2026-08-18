package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.inlay.InlayHints;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.InlayHintConfig;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
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
 * <p>Every assertion runs against the store alone, this arm having no other source; the provider
 * takes no schema snapshot at all now that its last snapshot-reading renderer moved onto a relation.
 */
class ClassificationHintsTest {

    /**
     * A schema whose coordinates cover every shape the arm answers: a claimed type, a field the
     * structural classifier reaches, a field an authored claim masks it at, a coordinate two
     * claims land on, and declarations nothing claims at all.
     */
    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
        type Query {
            placeholder: Int
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            row: FilmRow @service(service: {className: "%1$s", method: "makeFilmRow"})
            left: Contested @service(service: {className: "%1$s", method: "makeFilmRecord"})
            right: Contested @service(service: {className: "%1$s", method: "makeFilmPojo"})
        }

        type Film @table(name: "film") {
            title: String
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
        }

        type Note {
            text: String
        }

        type FilmCard {
            title: String
        }

        type FilmRow @table(name: "film") {
            title: String
        }

        type Contested {
            title: String
        }

        type FilmError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
            message: String
        }
        """.formatted(FIXTURE_SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, SDL, StoreFixture.backingClasses());
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
    void aTypeNoClaimNamesIsLabelledWithTheClassBackingIt() {
        // The payload type this arm used to be silent about. Nothing is authored at the
        // declaration: the label comes from the class a producer elsewhere in the schema hands
        // back, which is the whole of what the store knows the type to be.
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("R157FilmRecord");
    }

    @Test
    void aClaimBeatsABackingAtTheSameType() {
        // FilmRow is bound to a table and grounded on that table's generated record, so both
        // populations answer. The label is the claim, the backing following from it.
        var file = file("""
            type FilmRow @table(name: "film") {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("TABLE", "TABLE_COLUMN");
    }

    @Test
    void aTypeTwoProducersBackDifferentlyGetsNoLabel() {
        // Two producers naming different classes leaves nothing to prefer, and a label showing one
        // would name the class the generator does not bind. The hover states the disagreement.
        var file = file("""
            type Contested {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).isEmpty();
    }

    @Test
    void aDeclarationNoClassifierClaimsGetsNoLabel() {
        // Note is claimed by nothing and backed by nothing: no producer returns it and no member
        // of a backed class delivers it, so the arm has no opinion to render at either grain.
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
        var hints = InlayHints.compute(config(), file, Optional.empty(), fullRange());
        assertThat(hints).isEmpty();
    }

    // ===== Test helpers =====

    /** The classification arm alone: the inferred-directive arm has a test class of its own. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(false, true, false, false);
    }

    private static List<String> labels(FileSnapshot file, Range visible) {
        return InlayHints.compute(config(), file, Optional.of(store.handle()), visible)
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
