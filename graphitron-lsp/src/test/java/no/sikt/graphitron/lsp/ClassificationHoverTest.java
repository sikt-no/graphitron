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
 * A type declaration's hover classification block, over a real capture. The block is the classifiers
 * claiming the type plus the facts behind each, read from the relations that own them: there is no
 * projection variant name in it and no per-variant payload record behind it. A field's hover carries
 * no classification; {@code FieldInformationHoverTest} covers it.
 *
 * <p>Every assertion runs under an unavailable snapshot, which is how the class states that this
 * block's source is the store. The overlay beneath it still needs the snapshot, and its coverage
 * stays with {@code DeclarationHoverOverlayParityTest}.
 */
class ClassificationHoverTest {

    /**
     * A schema covering each shape the block answers: a claimed type, an error type, types a
     * producer backs once and twice over, and declarations nothing reaches at all.
     */
    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
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
    void aClaimedTypeNamesTheTableItBindsTo() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type Fi".length()))
            .contains("**TABLE**")
            .contains("`Film`")
            .contains("Table: `film`");
    }

    @Test
    void anErrorTypeListsItsHandlers() {
        var file = file("""
            type FilmError @error(handlers: [{handler: GENERIC, className: "java.lang.RuntimeException"}]) {
                message: String
            }
            """);
        assertThat(hoverAt(file, 0, "type FilmEr".length()))
            .contains("**ERROR**")
            .contains("Handler: `GENERIC java.lang.RuntimeException`");
    }

    @Test
    void anUnclaimedTypeNamesTheClassBackingIt() {
        // The payload type the block used to be silent about. No claim names it, so no heading, and
        // the class its producer hands back is what the store knows it to be.
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type FilmCa".length()))
            .doesNotContain("**")
            .contains("`FilmCard`")
            .contains("Backed by: `no.sikt.graphitron.lsp.fixtures.R157FilmRecord`");
    }

    @Test
    void aTypeTwoProducersBackDifferentlySaysSoRatherThanPickingOne() {
        // The population with no surface at all before this: every reader that needs one class is
        // silent, so without this line the author sees a payload type rendering like a plain object.
        var file = file("""
            type Contested {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "type Conte".length()))
            .contains("`Contested`")
            .contains("Backing contested, so nothing binds:")
            .contains("R157FilmPojo")
            .contains("R157FilmRecord");
    }

    @Test
    void aDeclarationNothingReachesGetsNoHover() {
        // Claimed by nothing and backed by nothing: a plain nesting object, which is the population
        // the backing lines must stay quiet about or they would fire on most of a schema.
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(hover(file, 0, "type No".length())).isEmpty();
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
        // another file resolves the same claims the base does.
        var file = file("""
            extend type Film {
                title: String
            }
            """);
        assertThat(hoverAt(file, 0, "extend type Fi".length())).contains("**TABLE**");
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
