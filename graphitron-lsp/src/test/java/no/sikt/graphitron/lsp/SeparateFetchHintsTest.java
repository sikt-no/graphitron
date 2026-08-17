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
 * The inlay hint's separate-fetch arm, over a real capture: one marker at each visible field whose
 * rows come from a statement of its own. The arm exists so an author can scan a type for its query
 * cost without hovering every field in it, which is why the marker is one word and the reason lives
 * in the hover.
 *
 * <p>Half of these cases pin silence, and the silences are the arm's substance rather than a gap in
 * it: an inlined child, a root field whose marker would repeat down the whole type, and a schema
 * with the toggle off.
 *
 * <p>The implicit split is marked here too, and it takes a real producer to pin: {@code FilmCard} is
 * grounded on a scanned class by the {@code @service} return the census resolves, so the marker on
 * its table-typed field comes from the store's own closure rather than from anything authored at the
 * coordinate. That case is the one an author cannot see in the schema text, which is the strongest
 * argument the arm has for existing.
 *
 * <p>Two silences remain not-a-claim and are deliberately not asserted: a child reached through a
 * connection wrapper, and the polymorphic fan-in. An unmarked field is "the store has no rule for
 * this" and never "this inlines", and the prohibition lives where it binds every reader, in the
 * relation's own comment.
 */
class SeparateFetchHintsTest {

    /** The scanned producer whose return grounds {@code FilmCard} on a class. */
    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
        type Query {
            films: [Film!]!
            filmsBySearch: [Film!]! @service(service: {className: "com.example.FilmService", method: "all"})
            card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
        }

        type FilmCard {
            title: String
            film: Film
        }

        type Film @table(name: "film") {
            title: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
        }

        type Language @table(name: "language") {
            name: String
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
    void aSplitChildIsMarkedAndItsInlinedSiblingIsNot() {
        // The pair is the whole point: two fields of the same shape onto the same table, one of
        // which costs a round trip. A marker that appeared on both would say nothing.
        var file = file("""
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(markedLines(file)).containsExactly(2);
    }

    @Test
    void aNonRootServiceFieldIsMarked() {
        var file = file("""
            type Film @table(name: "film") {
                rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            }
            """);
        assertThat(markedLines(file)).containsExactly(1);
    }

    @Test
    void aTableTypedFieldOfAClassBackedParentIsMarkedWithNothingAuthoredAtIt() {
        // Neither line carries a directive. The marker is on the one whose type is a table's,
        // because its parent arrives as a Java object a producer handed back and there is no
        // enclosing statement for that field's rows to come out of.
        var file = file("""
            type FilmCard {
                title: String
                film: Film
            }
            """);
        assertThat(markedLines(file)).containsExactly(2);
    }

    @Test
    void aColumnOfTheParentsOwnRowIsNotMarked() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(markedLines(file)).isEmpty();
    }

    @Test
    void aRootFieldIsNotMarkedBecauseEveryOneOfItsSiblingsWouldBe() {
        var file = file("""
            type Query {
                films: [Film!]!
                filmsBySearch: [Film!]! @service(service: {className: "com.example.FilmService", method: "all"})
            }
            """);
        assertThat(markedLines(file))
            .as("both are separate fetches, and saying so down a whole root type is noise")
            .isEmpty();
    }

    @Test
    void theMarkerIsOneWordWhateverTheReason() {
        // Several rules reaching one coordinate is several rows in the store and still one marker
        // here: the reader wants a signal, and which rules produced it is the hover's answer.
        var file = file("""
            type Film @table(name: "film") {
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            }
            """);
        assertThat(labels(file, fullRange()))
            .containsExactly(InlayHints.SEPARATE_FETCH_LABEL, InlayHints.SEPARATE_FETCH_LABEL);
    }

    @Test
    void theClassificationToggleDoesNotCarryTheMarker() {
        // The two arms are separate keys. An author who turned on classifier labels did not ask
        // for delivery markers beside them, and the classification arm's contract is that its
        // label is the classifier and only the classifier.
        var file = file("""
            type Film @table(name: "film") {
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        var hints = InlayHints.compute(new InlayHintConfig(false, true, false, false), file,
            Optional.of(store.handle()), LspSchemaSnapshot.unavailable(), fullRange());
        assertThat(labels(hints)).doesNotContain(InlayHints.SEPARATE_FETCH_LABEL);
    }

    @Test
    void bothArmsTogetherAnnotateTheSameDeclarationTwice() {
        var file = file("""
            type Film @table(name: "film") {
                rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            }
            """);
        var hints = InlayHints.compute(new InlayHintConfig(false, true, true, false), file,
            Optional.of(store.handle()), LspSchemaSnapshot.unavailable(), fullRange());
        assertThat(labels(hints))
            .as("the type's classifier, then the field's, then the field's delivery marker")
            .containsExactly("TABLE", "SERVICE", InlayHints.SEPARATE_FETCH_LABEL);
    }

    @Test
    void onlyDeclarationsInTheVisibleRangeAreMarked() {
        var file = file("""
            type Film @table(name: "film") {
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
                rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            }
            """);
        var hints = InlayHints.compute(config(), file, Optional.of(store.handle()),
            LspSchemaSnapshot.unavailable(), new Range(new Position(1, 0), new Position(1, 80)));
        assertThat(hints).hasSize(1);
    }

    @Test
    void noMarkersWithoutAStore() {
        var file = file("""
            type Film @table(name: "film") {
                languageSplit: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(InlayHints.compute(config(), file, Optional.empty(),
            LspSchemaSnapshot.unavailable(), fullRange())).isEmpty();
    }

    // ===== Helpers =====

    /** The 0-based lines carrying a marker, which is how a case names the field it means. */
    private static List<Integer> markedLines(FileSnapshot file) {
        var hints = InlayHints.compute(config(), file, Optional.of(store.handle()),
            LspSchemaSnapshot.unavailable(), fullRange());
        return hints.stream()
            .peek(hint -> assertThat(labelOf(hint)).isEqualTo(InlayHints.SEPARATE_FETCH_LABEL))
            .map(hint -> hint.getPosition().getLine())
            .toList();
    }

    private static List<String> labels(FileSnapshot file, Range range) {
        return labels(InlayHints.compute(config(), file, Optional.of(store.handle()),
            LspSchemaSnapshot.unavailable(), range));
    }

    private static List<String> labels(List<InlayHint> hints) {
        return hints.stream().map(SeparateFetchHintsTest::labelOf).toList();
    }

    private static String labelOf(InlayHint hint) {
        var either = hint.getLabel();
        return either.isLeft() ? either.getLeft() : either.getRight().toString();
    }

    /** The separate-fetch toggle alone, so nothing another arm renders can be mistaken for it. */
    private static InlayHintConfig config() {
        return new InlayHintConfig(false, false, true, false);
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }

    private static Range fullRange() {
        return new Range(new Position(0, 0), new Position(10_000, 0));
    }
}
