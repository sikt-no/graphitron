package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.NestedArgs;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Language;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms tree-sitter-graphql produces the {@code object_field} /
 * {@code object_value} / {@code list_value} node names the nested-arg
 * parser assumes, and that the descent reports the innermost match.
 */
class NestedArgsTest {

    @Test
    void cursorInsideObjectFieldOfListValueResolves() {
        // path: [{key: "fk_name"}] — cursor inside the empty quotes of `key`.
        String source = """
            type Foo {
                bar: Int @reference(path: [{key: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf('"') + 1;

        var nested = resolveNested(source, new Point(line, col));

        assertThat(nested.outerArgumentName()).isEqualTo("path");
        assertThat(nested.nestedFieldNameText()).isEqualTo("key");
    }

    @Test
    void cursorInsideObjectFieldOfPlainObjectValueResolves() {
        // record: {className: ""} — cursor inside the className empty quotes.
        String source = """
            type Foo @record(record: {className: ""}) {
                bar: Int
            }
            """;
        var lines = source.split("\n");
        int line = 0;
        int col = lines[line].indexOf("\"\"") + 1;

        var nested = resolveNested(source, new Point(line, col));

        assertThat(nested.outerArgumentName()).isEqualTo("record");
        assertThat(nested.nestedFieldNameText()).isEqualTo("className");
    }

    @Test
    void cursorOnFlatArgValueReturnsEmpty() {
        // @table(name: "FILM") has a flat string value; no nested fields.
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int
            }
            """;
        int col = source.indexOf("FILM") + 1;
        var directive = parseAndFindDirective(source, new Point(0, col));
        var nested = NestedArgs.findContaining(directive, new Point(0, col),
            source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(nested).isEmpty();
    }

    @Test
    void cursorOnSecondListElementReportsThatElement() {
        // path: [{key: "a"}, {key: ""}] — cursor on the second key value.
        String source = """
            type Foo {
                bar: Int @reference(path: [{key: "a"}, {key: ""}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        // Pick the second pair of empty quotes.
        int firstQuote = lines[line].indexOf('"');
        int secondPair = lines[line].indexOf("\"\"", firstQuote + 1);
        int col = secondPair + 1;

        var nested = resolveNested(source, new Point(line, col));

        assertThat(nested.nestedFieldNameText()).isEqualTo("key");
        // Second element's value is empty (just two quotes); confirm we
        // landed on the empty one, not the populated first element.
        var bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var rawValue = no.sikt.graphitron.lsp.parsing.Nodes.text(nested.nestedValue(), bytes);
        assertThat(rawValue).isEqualTo("\"\"");
    }

    private static NestedArgs.Nested resolveNested(String source, Point cursor) {
        var directive = parseAndFindDirective(source, cursor);
        var bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return NestedArgs.findContaining(directive, cursor, bytes)
            .orElseThrow(() -> new AssertionError("expected nested match at cursor"));
    }

    private static Directives.Directive parseAndFindDirective(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());
        var tree = parser.parse(source).orElseThrow();
        return Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
    }
}
