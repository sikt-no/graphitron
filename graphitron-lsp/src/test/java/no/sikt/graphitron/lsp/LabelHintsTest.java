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
 * The inlay-hint provider's label arm, over a real capture. A type is labelled with what it holds,
 * the table whose rows it holds or the class whose instances it does, and a field with what it
 * reads, its column, the method it calls or the table its statement lands on. Each label names a
 * fact a stored relation states, never a category.
 */
class LabelHintsTest {

    /**
     * A schema whose coordinates cover every label the arm renders and the places it stays quiet:
     * a table type and its column, a field reaching another table, a field calling a method, a type
     * a producer returns, one two producers return differently, and declarations nothing reaches.
     */
    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
        type Query {
            placeholder: Int
            films: [Film]
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            left: Contested @service(service: {className: "%1$s", method: "makeFilmRecord"})
            right: Contested @service(service: {className: "%1$s", method: "makeFilmPojo"})
        }

        type Film @table(name: "film") {
            title: String
            language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }

        type Language @table(name: "language") {
            name: String
        }

        type Note {
            text: String
        }

        type FilmCard {
            title: String
        }

        type Contested {
            title: String
        }
        """.formatted(FIXTURE_SERVICE);

    @TempDir
    static Path tmp;

    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.held().ofCatalog(tmp, SDL, StoreFixture.testClasses());
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void aTableTypeIsLabelledWithItsTableAndAFieldWithItsColumn() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("film", "film.title");
    }

    @Test
    void anObjectFieldIsLabelledWithTheTableItsStatementLandsOn() {
        var file = file("""
            type Film @table(name: "film") {
                language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("film", "\u2192 language");
    }

    @Test
    void aServiceFieldIsLabelledWithTheMethodItCalls() {
        var file = file("""
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            """.formatted(FIXTURE_SERVICE));
        assertThat(labels(file, fullRange())).containsExactly("R157Service.makeFilmRecord");
    }

    @Test
    void aTypeAProducerReturnsIsLabelledWithTheClassItHolds() {
        // Its field reads an accessor on the instance, which is no column to name.
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).containsExactly("R157FilmRecord");
    }

    @Test
    void aTypeHoldingTwoClassesGetsNoLabel() {
        // A label has room for one answer; the hover lists both.
        var file = file("""
            type Contested {
                title: String
            }
            """);
        assertThat(labels(file, fullRange())).isEmpty();
    }

    @Test
    void aTypeNothingReachesGetsNoLabel() {
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(labels(file, fullRange())).isEmpty();
    }

    @Test
    void aRootTypeHoldsNothingToLabel() {
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
        assertThat(labels(file, visible)).containsExactly("film", "film.title");
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

    /** The label arm alone: the inferred-directive arm has a test class of its own. */
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
