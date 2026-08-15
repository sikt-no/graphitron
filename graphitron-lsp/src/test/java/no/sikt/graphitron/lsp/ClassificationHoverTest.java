package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.hover.DeclarationHovers;
import no.sikt.graphitron.lsp.state.FileSnapshot;
import no.sikt.graphitron.lsp.state.WorkspaceFileTestSupport;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration hover's classification block, over a real capture. The block is the classifiers
 * claiming a declaration plus the facts behind each, read from the relations that own them: there is
 * no projection variant name in it and no per-variant payload record behind it.
 *
 * <p>Every assertion runs under an unavailable snapshot, which is how the class states that this
 * block's source is the store. The overlay beneath it still needs the snapshot, and its coverage
 * stays with {@code DeclarationHoverOverlayParityTest}.
 */
class ClassificationHoverTest {

    /**
     * A schema covering each shape the block answers: a column-matched field, one whose
     * {@code @field} binding matched under another name, a service field, a conflicted coordinate,
     * a DML mutation, a split child field claimed by nothing, a claimed type, an error type, and
     * declarations nothing reaches at all.
     */
    private static final String SDL = """
        type Query {
            films: [Film] @service(service: {className: "com.example.FilmService", method: "all"})
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
    void aColumnMatchedFieldNamesTheColumnItResolvedTo() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(file, 1, "    titl".length()))
            .contains("**TABLE_COLUMN**")
            .contains("`Film.title`")
            .contains("Column: `title`")
            .contains("Table: `film`")
            .contains("Matched by: `");
    }

    @Test
    void anAuthoredBindingShowsTheNameTheMatchRanOn() {
        // The @field binding is the author's redirection, so which spelling the classifier matched
        // on is the difference between the binding being read and the binding being ignored.
        var file = file("""
            type Film @table(name: "film") {
                filmTitle: String @field(name: "title")
            }
            """);
        assertThat(hoverAt(file, 1, "    filmTi".length()))
            .contains("**TABLE_COLUMN**")
            .contains("Column: `title`")
            .contains("Matched name: `title`");
    }

    @Test
    void aFieldMatchingUnderItsOwnNameShowsNoMatchedName() {
        var file = file("""
            type Film @table(name: "film") {
                title: String
            }
            """);
        assertThat(hoverAt(file, 1, "    titl".length())).doesNotContain("Matched name");
    }

    @Test
    void aServiceFieldNamesItsMethodAndSaysItLaunchesItsOwnQuery() {
        var file = file("""
            type Film @table(name: "film") {
                rating: String @service(service: {className: "com.example.FilmService", method: "rating"})
            }
            """);
        assertThat(hoverAt(file, 1, "    rati".length()))
            .contains("**SERVICE**")
            .contains("Service: `com.example.FilmService#rating`")
            .contains("Launches its own query:")
            .contains("the service fetches independently of the parent's SELECT");
    }

    @Test
    void aSplitChildFieldGetsItsJoinPathAndItsRoundTripWithoutAnyClaim() {
        // Nothing claims a table-typed child: no directive names what it is, and the structural
        // classifier only reaches leaf fields. The round-trip answer is the whole reason an author
        // hovers here, so the block opens on the claim-independent facts alone.
        var file = file("""
            type Film @table(name: "film") {
                language: Language @splitQuery @reference(path: [{key: "film_language_id_fkey"}])
            }
            """);
        var md = hoverAt(file, 1, "    langu".length());
        assertThat(md)
            .doesNotContain("**")
            .contains("`Film.language`")
            .contains("Join path:")
            .contains("`film_language_id_fkey` → `language`")
            .contains("Launches its own query:")
            .contains("`@splitQuery` defers the fetch to a batched DataLoader call");
    }

    @Test
    void aRootFieldIsItsOwnEntryPoint() {
        var file = file("""
            type Query {
                films: [Film] @service(service: {className: "com.example.FilmService", method: "all"})
            }
            """);
        assertThat(hoverAt(file, 1, "    fil".length()))
            .contains("**SERVICE**")
            .contains("a root operation field is its own entry point");
    }

    @Test
    void aConflictedCoordinateNamesBothClaimsAndCarriesBothSetsOfFacts() {
        var file = file("""
            type Film @table(name: "film") {
                filmId: ID @nodeId(typeName: "Film") @service(service: {className: "com.example.FilmService", method: "id"})
            }
            """);
        assertThat(hoverAt(file, 1, "    filmI".length()))
            .contains("**NODE_ID, SERVICE**")
            .contains("Node type: `Film`")
            .contains("Service: `com.example.FilmService#id`");
    }

    @Test
    void aDmlMutationNamesItsOperationAndTable() {
        var file = file("""
            type Mutation {
                deleteFilm(filmId: ID): ID @mutation(typeName: DELETE, table: "film")
            }
            """);
        assertThat(hoverAt(file, 1, "    delet".length()))
            .contains("**MUTATION**")
            .contains("Operation: `DELETE`")
            .contains("Table: `film`");
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
    void aDeclarationNothingReachesGetsNoHover() {
        var file = file("""
            type Note {
                text: String
            }
            """);
        assertThat(hover(file, 0, "type No".length())).isEmpty();
        assertThat(hover(file, 1, "    tex".length())).isEmpty();
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
            file, Optional.empty(), LspSchemaSnapshot.unavailable(), new Point(0, "type Fi".length())))
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
        assertThat(hoverAt(file, 1, "    titl".length()))
            .contains("**TABLE_COLUMN**")
            .contains("`Film.title`");
    }

    // ===== Helpers =====

    private static String hoverAt(FileSnapshot file, int line, int column) {
        return hover(file, line, column).orElseThrow();
    }

    private static Optional<String> hover(FileSnapshot file, int line, int column) {
        return DeclarationHovers.compute(
                file, Optional.of(store.handle()), LspSchemaSnapshot.unavailable(), new Point(line, column))
            .map(h -> h.getContents().getRight().getValue());
    }

    private static FileSnapshot file(String source) {
        return WorkspaceFileTestSupport.snapshot(source);
    }
}
