package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TreeSitterGraphql;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @reference(path: [{key: "..."}, {table: "..."}])}.
 * Exercises the nested-arg parser end-to-end against the FK-references
 * shape produced by the catalog builder, and confirms cursor placement
 * in the wrong nested field stays empty.
 */
class ReferenceCompletionsTest {

    @Test
    void keyCompletionReturnsForeignKeysOfEnclosingTable() {
        // film holds an FK to language and an inverse FK from film_actor.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var items = run(filmCatalog(), source, new TSPoint(line, col));

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactlyInAnyOrder("FILM__FILM_LANGUAGE_ID_FKEY", "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY");
    }

    @Test
    void tableCompletionReturnsAllTables() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var items = run(filmCatalog(), source, new TSPoint(line, col));

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactlyInAnyOrder("film", "language", "film_actor");
    }

    @Test
    void cursorOutsideNestedValueReturnsEmpty() {
        // Cursor on the nested field's key name, not on its value.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__FK"}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("key:") + 1;

        var items = run(filmCatalog(), source, new TSPoint(line, col));

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmptyForKey() {
        String source = """
            type Foo @table(name: "missing") {
                bar: Int @reference(path: [{key: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var items = run(filmCatalog(), source, new TSPoint(line, col));

        assertThat(items).isEmpty();
    }

    @Test
    void unknownNestedFieldReturnsEmpty() {
        // 'condition' is a real field on ReferenceElement but slice 2 does
        // not yet plug autocomplete for it; the dispatcher returns empty.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{condition: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var items = run(filmCatalog(), source, new TSPoint(line, col));

        assertThat(items).isEmpty();
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(
        CompletionData data, String source, TSPoint cursor
    ) {
        var parser = new TSParser();
        parser.setLanguage(new TreeSitterGraphql());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parseString(null, source);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return ReferenceCompletions.generate(data, directive, cursor, bytes);
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film", "", CompletionData.SourceLocation.UNKNOWN,
            List.of(),
            List.of(
                CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false),
                CompletionData.Reference.of("film_actor", "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY", true)
            )
        );
        var language = new CompletionData.Table(
            "language", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of()
        );
        var filmActor = new CompletionData.Table(
            "film_actor", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of()
        );
        return new CompletionData(List.of(film, language, filmActor), List.of(), List.of());
    }
}
