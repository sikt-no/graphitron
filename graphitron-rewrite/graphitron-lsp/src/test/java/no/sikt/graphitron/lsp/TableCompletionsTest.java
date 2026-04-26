package no.sikt.graphitron.lsp;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TreeSitterGraphql;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end completion happy-path. Parses a schema with the cursor inside
 * {@code @table(name: "...")} and asserts the completion list comes back as
 * the catalog's table set.
 */
class TableCompletionsTest {

    @Test
    void tableNameCompletionReturnsCatalogTables() {
        // Cursor sits inside the empty quoted string after `name: `.
        String source = """
            type Foo @table(name: "") {
                bar: Int
            }
            """;
        TSPoint cursor = new TSPoint(0, source.indexOf('"') + 1);

        CompletionData data = new CompletionData(
            List.of(
                table("FILM", "Watch movies"),
                table("ACTOR", "")
            ),
            List.of(),
            List.of()
        );

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterGraphql());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parseString(null, source);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));

        var items = TableCompletions.generate(data, directive, cursor, bytes);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).getLabel()).isEqualTo("FILM");
        assertThat(items.get(1).getLabel()).isEqualTo("ACTOR");
    }

    @Test
    void cursorOutsideArgumentReturnsEmpty() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int
            }
            """;
        // Cursor on the directive name, not inside an argument.
        TSPoint cursor = new TSPoint(0, source.indexOf("@table") + 1);

        var parser = new TSParser();
        parser.setLanguage(new TreeSitterGraphql());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parseString(null, source);
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow();

        var data = new CompletionData(List.of(table("FILM", "")), List.of(), List.of());
        var items = TableCompletions.generate(data, directive, cursor, bytes);

        assertThat(items).isEmpty();
    }

    private static CompletionData.Table table(String name, String description) {
        return new CompletionData.Table(
            name,
            description,
            CompletionData.SourceLocation.UNKNOWN,
            List.of(),
            List.of()
        );
    }
}
