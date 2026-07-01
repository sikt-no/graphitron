package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeClassification;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Language;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @reference(path: [{key: "..."}, {table: "..."}])}.
 * Exercises the nested-arg parser end-to-end against the FK-references
 * shape produced by the catalog builder, and confirms cursor placement
 * in the wrong nested field stays empty.
 */
class ReferenceCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

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

        var items = run(filmCatalog(), source, new Point(line, col));

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactlyInAnyOrder("FILM__FILM_LANGUAGE_ID_FKEY", "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY");
    }

    @Test
    void tableCompletionRoutesThroughTableCompletionsNotHere() {
        // Per R119 phase 2 split, ReferenceCompletions narrows to the FK
        // (CatalogFkBinding) arm. Table completion at
        // @reference(path: [{table:}]) is the ReferenceElement.table
        // coordinate's CatalogTableBinding, served by TableCompletions.
        // ReferenceCompletions returns empty here.
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{table: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var items = run(filmCatalog(), source, new Point(line, col));

        assertThat(items).isEmpty();
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

        var items = run(filmCatalog(), source, new Point(line, col));

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmptyForKey() {
        // R216 — snapshot is the source of truth for the type-to-table binding. When the
        // classifier maps Foo to a table the catalog doesn't know about, key completion
        // returns empty.
        String source = """
            type Foo @table(name: "missing") {
                bar: Int @reference(path: [{key: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var fooMissingSnapshot = new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("Foo", new TypeClassification.Table("missing")));
        var items = run(filmCatalog(), source, new Point(line, col), fooMissingSnapshot);

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

        var items = run(filmCatalog(), source, new Point(line, col));

        assertThat(items).isEmpty();
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(
        CompletionData data, String source, Point cursor
    ) {
        return run(data, source, cursor, fooFilmSnapshot());
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(
        CompletionData data, String source, Point cursor, LspSchemaSnapshot snapshot
    ) {
        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return ReferenceCompletions.generate(VOCAB, data, snapshot, context, directive, bytes);
    }

    private static LspSchemaSnapshot fooFilmSnapshot() {
        return new LspSchemaSnapshot.Built.Current(
            List.of(), Map.of(), Map.of(),
            Map.of(), Map.of("Foo", new TypeClassification.Table("film")));
    }

    private static CompletionData filmCatalog() {
        var film = new CompletionData.Table(
            "film", "", null,
            List.of(),
            List.of(
                CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false),
                CompletionData.Reference.of("film_actor", "FILM_ACTOR__FILM_ACTOR_FILM_ID_FKEY", true)
            )
        );
        var language = new CompletionData.Table(
            "language", "", null, List.of(), List.of()
        );
        var filmActor = new CompletionData.Table(
            "film_actor", "", null, List.of(), List.of()
        );
        return new CompletionData(List.of(film, language, filmActor), List.of(), List.of());
    }
}
