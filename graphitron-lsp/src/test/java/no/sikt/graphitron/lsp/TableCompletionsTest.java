package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Language;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end completion happy-path. Parses a schema with the cursor inside
 * {@code @table(name: "...")} and asserts the completion list comes back as
 * the catalog's table set.
 */
class TableCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void tableNameCompletionReturnsCatalogTables() {
        // Cursor sits inside the empty quoted string after `name: `.
        String source = """
            type Foo @table(name: "") {
                bar: Int
            }
            """;
        Point cursor = new Point(0, source.indexOf('"') + 1);

        CompletionData data = new CompletionData(
            List.of(
                table("FILM", "Watch movies"),
                table("ACTOR", "")
            ),
            List.of(),
            List.of()
        );

        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));

        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        var items = locOpt
            .map(loc -> TableCompletions.generate(VOCAB, data, no.sikt.graphitron.rewrite.catalog.SourceWalker.Index.EMPTY, no.sikt.graphitron.lsp.completions.CompletionContext.from(loc, bytes)))
            .orElseGet(List::of);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getLabel()).isEqualTo("FILM");
        assertThat(items.get(1).getLabel()).isEqualTo("ACTOR");
    }

    @Test
    void referenceElementTableNestedFieldAlsoCompletesCatalogTables() {
        // ReferenceElement.table is a CatalogTableBinding in the canonical
        // overlay. The same provider that fires on
        // @table(name:) now also fires on @reference(path: [{table:}]) —
        // the directive-name switch dropped, dispatch goes through the
        // coordinate's behavior arm.
        String source = """
            type Foo {
                bar: Int @reference(path: [{table: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        Point cursor = new Point(line, lines[line].indexOf("\"\"") + 1);

        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow();

        var data = new CompletionData(
            List.of(table("FILM", ""), table("ACTOR", "")),
            List.of(),
            List.of()
        );

        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        var items = locOpt
            .map(loc -> TableCompletions.generate(VOCAB, data, no.sikt.graphitron.rewrite.catalog.SourceWalker.Index.EMPTY, no.sikt.graphitron.lsp.completions.CompletionContext.from(loc, bytes)))
            .orElseGet(List::of);

        assertThat(items).extracting(i -> i.getLabel()).containsExactlyInAnyOrder("FILM", "ACTOR");
    }

    @Test
    void cursorOutsideArgumentReturnsEmpty() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int
            }
            """;
        // Cursor on the directive name, not inside an argument.
        Point cursor = new Point(0, source.indexOf("@table") + 1);

        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow();

        var data = new CompletionData(List.of(table("FILM", "")), List.of(), List.of());
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        var items = locOpt
            .map(loc -> TableCompletions.generate(VOCAB, data, no.sikt.graphitron.rewrite.catalog.SourceWalker.Index.EMPTY, no.sikt.graphitron.lsp.completions.CompletionContext.from(loc, bytes)))
            .orElseGet(List::of);

        assertThat(items).isEmpty();
    }

    private static CompletionData.Table table(String name, String description) {
        return new CompletionData.Table(
            name,
            description,
            null,
            List.of(),
            List.of()
        );
    }
}
