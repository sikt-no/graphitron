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
 * A field's hover, over a real capture: what the store states about the field, a line per fact,
 * with no classification in it. The cases are the shapes a field's value takes, a column, a column
 * matched by its generated name, no column, a type reached through a path, and the code a field
 * calls, and where its query starts, from its own type's table or one it inherits.
 */
class FieldInformationHoverTest {

    private static final String FIXTURE_SERVICE = "no.sikt.graphitron.lsp.fixtures.R157Service";

    private static final String SDL = """
        interface Node { id: ID! }

        type Query {
            films: [Film]
            twins: [Twins]
            card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
        }

        type Film @table(name: "film") {
            "The film's title."
            title: String
            titel: String
            language: Language @reference(path: [{key: "film_language_id_fkey"}])
            details: FilmDetails
        }

        type FilmDetails {
            title: String
        }

        type Language implements Node @table(name: "language") @node {
            id: ID!
            name: String
        }

        type Twins @table(name: "case_twins") {
            capital: String @field(name: "CAPITAL_CODE")
        }

        type FilmCard {
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
    void aColumnFieldNamesItsColumnAndWhereItsQueryStarts() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(file, 1, "    titl".length()))
            .contains("`Film.title: String`")
            .contains("The film's title.")
            .contains("**Column** `film.title` varchar")
            .contains("not null")
            .contains("**Source** `film`, the parent type's table")
            .doesNotContain("TABLE_COLUMN")
            .doesNotContain("**Defects**");
    }

    @Test
    void aColumnMatchedByItsGeneratedNameSaysSo() {
        var file = file("""
            type Twins @table(name: "case_twins") {
                capital: String @field(name: "CAPITAL_CODE")
            }
            """);
        assertThat(hoverAt(file, 1, "    capi".length()))
            .contains("**Column** `case_twins.Code`")
            .contains("matched by jOOQ name");
    }

    @Test
    void aSpellingNamingNoColumnSaysSoAndCarriesTheDefect() {
        var file = file("""
            type Film @table(name: "film") {
                titel: String
            }
            """);
        assertThat(hoverAt(file, 1, "    tite".length()))
            .contains("**Column** no column")
            .contains("**Defects** `COLUMN_UNRESOLVED`");
    }

    @Test
    void aFieldThroughAPathNamesItsTargetItsNodeAndItsPath() {
        var file = file("""
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        assertThat(hoverAt(file, 1, "    langu".length()))
            .contains("`Film.language: Language`")
            .contains("**Target** `Language` → `public.language`")
            .contains("**Node** `Language` · id `Language` · keys `language_id`")
            .contains("**Path** 1 element")
            .doesNotContain("**Column**");
    }

    @Test
    void aNestingTypesFieldSaysItsTableIsInherited() {
        var file = file("""
            type FilmDetails {
                title: String
            }
            """);
        assertThat(hoverAt(file, 1, "    titl".length()))
            .contains("**Column** `film.title`")
            .contains("**Source** `film`, inherited through `FilmDetails`, which has no table of its own");
    }

    @Test
    void aServiceFieldNamesTheMethodItCalls() {
        var file = file("""
            type Query {
                card: FilmCard @service(service: {className: "%s", method: "makeFilmRecord"})
            }
            """.formatted(FIXTURE_SERVICE));
        assertThat(hoverAt(file, 1, "    car".length()))
            .contains("**Calls** `@service` `R157Service.makeFilmRecord(")
            .doesNotContain("**Source**");
    }

    @Test
    void aScalarWithNoSourceTableNamesNoColumn() {
        // Read off the object its producer returns, which is no column to report missing.
        var file = file("""
            type FilmCard {
                title: String
            }
            """);
        assertThat(hoverAt(file, 1, "    titl".length()))
            .contains("`FilmCard.title: String`")
            .doesNotContain("**Column**");
    }

    @Test
    void aConfiguredTagIsWhatGraphitronAdds(@TempDir Path dir) {
        try (var tagged = StoreFixture.tagged(dir, "type Query { ping: String }\n", "catalog")) {
            var file = file("""
                type Query { ping: String }
                """);
            assertThat(DeclarationHovers.compute(file, Optional.of(tagged.handle()),
                    new Point(0, "type Query { pin".length()))
                .map(h -> h.getContents().getRight().getValue()).orElseThrow())
                .contains("**Added** `@tag(name: \"catalog\")` (configured)");
        }
    }

    @Test
    void aFieldTheStoreDoesNotKnowGetsNoHover() {
        var file = file("""
            type Unknown {
                mystery: String
            }
            """);
        assertThat(hover(file, 1, "    myster".length())).isEmpty();
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
