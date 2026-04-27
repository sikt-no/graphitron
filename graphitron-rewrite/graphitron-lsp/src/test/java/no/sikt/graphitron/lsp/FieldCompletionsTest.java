package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.FieldCompletions;
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
 * End-to-end coverage for {@code @field(name: "...")} column autocomplete.
 * The interesting bit is the context resolution: the LSP must walk up
 * from the field's directive to the enclosing type's {@code @table} to
 * pick which table's columns to suggest.
 */
class FieldCompletionsTest {

    @Test
    void columnNameCompletionReturnsTableColumns() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        // Cursor inside the empty quoted argument value.
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        TSPoint cursor = new TSPoint(line, col);

        var items = run(filmCatalog(), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("FILM_ID", "TITLE", "LANGUAGE_ID");
    }

    @Test
    void cursorOnFieldDirectiveWithoutEnclosingTableReturnsEmpty() {
        // Type has no @table directive, so we cannot resolve the column set.
        String source = """
            type Foo {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        TSPoint cursor = new TSPoint(line, col);

        var items = run(filmCatalog(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmpty() {
        // Enclosing type points at a table the catalog does not know.
        String source = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        TSPoint cursor = new TSPoint(line, col);

        var items = run(filmCatalog(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOutsideNameArgReturnsEmpty() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "title")
            }
            """;
        // Cursor on the @field directive name, not inside the argument.
        int line = 1;
        int col = source.split("\n")[line].indexOf("@field") + 1;
        TSPoint cursor = new TSPoint(line, col);

        var items = run(filmCatalog(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void interfaceTypeWithTableDirectiveAlsoResolvesColumns() {
        // @table on an interface — same context-resolution path.
        String source = """
            interface Movie @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        TSPoint cursor = new TSPoint(line, col);

        var items = run(filmCatalog(), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .contains("FILM_ID", "TITLE");
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
        return FieldCompletions.generate(data, directive, cursor, bytes);
    }

    private static CompletionData filmCatalog() {
        return new CompletionData(
            List.of(new CompletionData.Table(
                "FILM",
                "Movies the rental store carries",
                CompletionData.SourceLocation.UNKNOWN,
                List.of(
                    CompletionData.Column.of("FILM_ID", "Integer", false, ""),
                    CompletionData.Column.of("TITLE", "String", false, ""),
                    CompletionData.Column.of("LANGUAGE_ID", "Integer", true, "")
                ),
                List.of()
            )),
            List.of(),
            List.of()
        );
    }
}
