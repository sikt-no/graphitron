package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A type declaration's hover, over a real capture: what the store states about the type, a line per
 * fact, with no classification in it. The cases are the results a type can hold, a row of a table,
 * an instance of the class a producer returns, two classes at once, and nothing at all, and the
 * lines a node type and an error type add.
 */
class TypeInformationHoverTest {

    /**
     * A schema covering each result a type can hold: a table type, a node over one, an error type,
     * types a producer returns once and twice over, and a type nothing reaches.
     */
    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
        interface Node { id: ID! }

        type Query {
            films: [Film] @service(service: {className: "com.example.FilmService", method: "all"})
            card: FilmCard @service(service: {className: "%1$s", method: "makeFilmRecord"})
            left: Contested @service(service: {className: "%1$s", method: "makeFilmRecord"})
            right: Contested @service(service: {className: "%1$s", method: "makeFilmPojo"})
        }

        type Mutation {
            deleteFilm(filmId: ID): ID @mutation(typeName: DELETE, table: "film")
        }

        type Film @table(name: "film") {
            title: String
            filmTitle: String @field(name: "title")
            rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
            language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
        }

        "A language a film is spoken in."
        type Language implements Node @table(name: "language") @node {
            id: ID!
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

        type FilmError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
            message: String
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
    void aTableTypeHoldsARowOfItsTable() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type Fi".length()))
            .contains("`type Film`")
            .contains("**Holds** a row of `public.film`")
            .doesNotContain("TABLE**")
            .doesNotContain("**Node**");
    }

    @Test
    void aNodeTypeNamesItsIdAndKeys() {
        var file = file("""
            type Language implements Node @table(name: "language") @node {
                id: ID!
            }
            """);
        assertThat(hoverAt(file, 0, "type Langu".length()))
            .contains("A language a film is spoken in.")
            .contains("**Holds** a row of `public.language`")
            .contains("**Node** id `Language` · keys `language_id`");
    }

    @Test
    void anErrorTypeListsItsHandlers() {
        var file = file("""
            type FilmError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                message: String
            }
            """);
        assertThat(hoverAt(file, 0, "type FilmEr".length()))
            .contains("**Handles** `GENERIC java.lang.RuntimeException`");
    }

    @Test
    void aTypeAProducerReturnsHoldsTheClassItReturns() {
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type FilmCa".length()))
            .contains("`type FilmCard`")
            .contains("**Holds** an instance of `no.sikt.graphitron.lsp.fixtures.R157FilmRecord`");
    }

    @Test
    void aTypeTwoProducersReturnDifferentlyHoldsBothRatherThanOne() {
        var file = file("""
            type Contested {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type Conte".length()))
            .contains("**Holds** an instance of `no.sikt.graphitron.lsp.fixtures.R157FilmPojo`")
            .contains("**Holds** an instance of `no.sikt.graphitron.lsp.fixtures.R157FilmRecord`");
    }

    @Test
    void aTypeNothingReachesHoldsNothing() {
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(hoverAt(file, 0, "type No".length()))
            .contains("`type Note`")
            .doesNotContain("**Holds**");
    }

    @Test
    void aCursorInsideADirectiveDoesNotFire() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hover(file, 0, "type Film @table(na".length())).isEmpty();
    }

    @Test
    void noBlockWithoutAStore() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(DeclarationHovers.compute(
            file, Optional.empty(), new Point(0, "type Fi".length())))
            .isEmpty();
    }

    // ===== extend type X { ... } parity =====

    @Test
    void anExtensionSiteReadsTheTypeItExtends() {
        // Both grains are name-keyed on the type, so an extension whose base declaration lives in
        // another file reads what the base does.
        var file = file("""
            extend type Film {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "extend type Fi".length())).contains("**Holds** a row of `public.film`");
        assertThat(hoverAt(file, 1, "    titl".length())).contains("**Column** `film.title`");
    }

    // ===== Helpers =====

    private static String hoverAt(FileSnapshot file, int line, int column) {
        return hover(file, line, column).orElseThrow();
    }

    private static Optional<String> hover(FileSnapshot file, int line, int column) {
        return DeclarationHovers.compute(
                file, Optional.of(store.handle()), new Point(line, column))
            .map(h -> h.getContents().getRight().getValue());
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
