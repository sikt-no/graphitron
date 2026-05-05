package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.definition.Definitions;
import no.sikt.graphitron.lsp.state.WorkspaceFile;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Goto-definition for known directive arguments. Maps the cursor to a
 * {@code Location} on the jOOQ-generated source tree pre-populated by
 * {@code CatalogBuilder}; the LSP just looks the location up.
 *
 * <p>Phase 4 covers the catalog-driven dispatch and the fall-throughs
 * (unknown name, unknown table, unknown nested field, missing source
 * location). Real source-line refinement waits until JavaParser is
 * adopted; here every entry's range is {@code (0:0)}.
 */
class DefinitionsTest {

    private static final String FILM_URI = "file:///fake/jooq/Film.java";
    private static final String LANGUAGE_URI = "file:///fake/jooq/Language.java";
    private static final String KEYS_URI = "file:///fake/jooq/Keys.java";

    @Test
    void tableDefinitionMapsToTableSourceUri() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");

        var loc = Definitions.compute(file, filmCatalog(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(FILM_URI);
        assertThat(loc.getRange().getStart().getLine()).isZero();
    }

    @Test
    void unknownTableReturnsEmpty() {
        var file = file("type Foo @table(name: \"GHOST\") { bar: Int }");
        var pos = pointAt(file, 0, "GHOST");
        assertThat(Definitions.compute(file, filmCatalog(), pos)).isEmpty();
    }

    @Test
    void fieldDefinitionMapsToTableSourceUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "title")
            }
            """);
        var pos = pointAt(file, 1, "title");

        var loc = Definitions.compute(file, filmCatalog(), pos).orElseThrow();
        // Phase 4 sends columns to the same file as the owning table; line
        // refinement waits for JavaParser.
        assertThat(loc.getUri()).isEqualTo(FILM_URI);
    }

    @Test
    void referenceKeyMapsToKeysSourceUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FILM_LANGUAGE_ID_FKEY"}])
            }
            """);
        var pos = pointAt(file, 1, "FILM__FILM_LANGUAGE_ID_FKEY");

        var loc = Definitions.compute(file, filmCatalog(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(KEYS_URI);
    }

    @Test
    void referenceTableMapsToTargetTableUri() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: "language"}])
            }
            """);
        var pos = pointAt(file, 1, "language");

        var loc = Definitions.compute(file, filmCatalog(), pos).orElseThrow();
        assertThat(loc.getUri()).isEqualTo(LANGUAGE_URI);
    }

    @Test
    void cursorOnDirectiveNameReturnsEmpty() {
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        // Cursor on the @table directive name token, not on its argument.
        int col = "type Foo @t".length();
        assertThat(Definitions.compute(file, filmCatalog(), new Point(0, col))).isEmpty();
    }

    @Test
    void unknownColumnReturnsEmpty() {
        var file = file("""
            type Foo @table(name: "film") {
                bar: Int @field(name: "GHOST")
            }
            """);
        var pos = pointAt(file, 1, "GHOST");
        assertThat(Definitions.compute(file, filmCatalog(), pos)).isEmpty();
    }

    @Test
    void unknownLocationProducesEmpty() {
        // Same shape as filmCatalog but without source URIs; every
        // location collapses to UNKNOWN, so goto-def returns empty.
        var unsourcedCatalog = new CompletionData(
            List.of(new CompletionData.Table(
                "film", "", CompletionData.SourceLocation.UNKNOWN,
                List.of(CompletionData.Column.of("title", "String", false, "")),
                List.of()
            )),
            List.of(),
            List.of()
        );
        var file = file("type Foo @table(name: \"film\") { bar: Int }");
        var pos = pointAt(file, 0, "film");
        assertThat(Definitions.compute(file, unsourcedCatalog, pos)).isEmpty();
    }

    private static Point pointAt(WorkspaceFile file, int line, String token) {
        String source = new String(file.source(), java.nio.charset.StandardCharsets.UTF_8);
        var lines = source.split("\n");
        int col = lines[line].indexOf(token);
        if (col < 0) {
            throw new AssertionError("token '" + token + "' not on line " + line + ": " + lines[line]);
        }
        return new Point(line, col + Math.max(1, token.length() / 2));
    }

    private static WorkspaceFile file(String source) {
        return new WorkspaceFile(1, source);
    }

    private static CompletionData filmCatalog() {
        var filmDef = new CompletionData.SourceLocation(FILM_URI, 0, 0);
        var langDef = new CompletionData.SourceLocation(LANGUAGE_URI, 0, 0);
        var keysDef = new CompletionData.SourceLocation(KEYS_URI, 0, 0);

        var film = new CompletionData.Table(
            "film", "", filmDef,
            List.of(
                new CompletionData.Column("film_id", "Integer", false, "", filmDef),
                new CompletionData.Column("title", "String", false, "", filmDef)
            ),
            List.of(
                new CompletionData.Reference("language", "FILM__FILM_LANGUAGE_ID_FKEY", false, keysDef)
            )
        );
        var language = new CompletionData.Table(
            "language", "", langDef,
            List.of(),
            List.of()
        );
        return new CompletionData(List.of(film, language), List.of(), List.of());
    }
}
