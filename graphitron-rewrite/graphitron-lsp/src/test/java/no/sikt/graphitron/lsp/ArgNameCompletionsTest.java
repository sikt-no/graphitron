package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Argument-name completion driven off the parsed registry. Pins the two
 * cursor-position cases the spec calls out: top-level (cursor inside a
 * directive's parens but not on any argument) and nested (cursor inside
 * a nested {@code object_value} but not on any object_field).
 */
class ArgNameCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void cursorAfterCommaCompletesRemainingArgNames() {
        // @service(service: {...}, |) — cursor after the trailing comma.
        // Tree-sitter parses the directive's outer node to include the
        // trailing comma + closing paren when at least one well-formed
        // arg is present. The user-facing scenario: developer typed the
        // first arg + comma, expects completion on the next arg name.
        String source = """
            type Foo {
                bar: Int @service(service: {className: "x"}, )
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        // Cursor on the space after the comma.
        int col = lines[line].indexOf(", ") + 2;

        var items = run(source, new Point(line, col));

        // @service has two args: service (already present) + contextArguments.
        assertThat(items).extracting(i -> i.getLabel())
            .contains("contextArguments");
    }

    @Test
    void cursorBetweenObjectBracesCompletesInputFieldNames() {
        // @reference(path: [{|}]) — cursor in the empty object_value;
        // the input type at this nesting level is ReferenceElement, so
        // the completions are its three fields.
        String source = """
            type Foo {
                bar: Int @reference(path: [{}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("{}") + 1;

        var items = run(source, new Point(line, col));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("condition", "key", "table");
    }

    @Test
    void cursorInsideNestedObjectCompletesEcrFieldNames() {
        // @reference(path: [{condition: {|}}]) — cursor in the inner
        // ExternalCodeReference's object_value.
        String source = """
            type Foo {
                bar: Int @reference(path: [{condition: {}}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("condition: {") + "condition: {".length();

        var items = run(source, new Point(line, col));

        assertThat(items).extracting(i -> i.getLabel())
            .containsExactlyInAnyOrder("className", "method", "name", "argMapping");
    }

    @Test
    void cursorOnUnknownDirectiveReturnsEmpty() {
        // Use a fixture that parses cleanly (parens claimed because there's
        // a well-formed arg) but names a directive the registry doesn't
        // declare. Arg-name completion should not fire for unknown
        // directives — the unknown-directive diagnostic surfaces the typo.
        String source = """
            type Foo @notADirective(name: "x") { bar: Int }
            """;
        // Cursor inside the outer parens but on whitespace just before the arg
        // — wait, that position is inside `name`'s range. Land cursor on the
        // closing paren instead, which is inside outer but outside the only arg.
        int col = source.indexOf(") ") + 1;

        var items = run(source, new Point(0, col));

        assertThat(items).isEmpty();
    }

    @Test
    void cursorOnArgValueReturnsEmpty() {
        // Cursor on the value side of a known arg — value completers own
        // this position, not the arg-name completer.
        String source = """
            type Foo @table(name: "") { bar: Int }
            """;
        int col = source.indexOf("\"\"") + 1;

        var items = run(source, new Point(0, col));

        assertThat(items).isEmpty();
    }

    private static java.util.List<org.eclipse.lsp4j.CompletionItem> run(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor " + cursor));
        return ArgNameCompletions.generate(VOCAB, directive, cursor, bytes);
    }
}
