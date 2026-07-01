package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link LspVocabulary#locateAt} node-kind dispatch and the
 * {@link CompletionContext#replaceRangeFor(io.github.treesitter.jtreesitter.Node, byte[])}
 * computation it feeds. Direct unit-level coverage; the provider-side
 * regression pins live in {@code CompletionTextEditTest}.
 */
class LspVocabularyLocateAtTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void stringValueLeaf_replaceRangeStripsSingleQuoteDelimiters() {
        String source = "type Foo @table(name: \"film\") { x: Int }\n";
        Point cursor = new Point(0, source.indexOf('"') + 2);

        var loc = locateAt(source, cursor);

        assertThat(loc.coordinate()).isEqualTo(new SchemaCoordinate.DirectiveArg("table", "name"));
        assertThat(loc.leafNode().getType()).isEqualTo("string_value");

        var range = CompletionContext.from(loc, source.getBytes(StandardCharsets.UTF_8)).replaceRange();
        int innerStart = source.indexOf("film");
        assertThat(range).isEqualTo(new Range(
            new Position(0, innerStart),
            new Position(0, innerStart + "film".length())));
    }

    @Test
    void emptyStringLiteral_replaceRangeIsZeroWidthAtCursor() {
        String source = "type Foo @table(name: \"\") { x: Int }\n";
        int innerCol = source.indexOf("\"\"") + 1;
        Point cursor = new Point(0, innerCol);

        var loc = locateAt(source, cursor);
        var range = CompletionContext.from(loc, source.getBytes(StandardCharsets.UTF_8)).replaceRange();

        assertThat(range.getStart()).isEqualTo(new Position(0, innerCol));
        assertThat(range.getEnd()).isEqualTo(new Position(0, innerCol));
    }

    @Test
    void blockStringValueLeaf_replaceRangeStripsTripleQuoteDelimiters() {
        String source = "type Foo @table(name: \"\"\"film\"\"\") { x: Int }\n";
        int innerStart = source.indexOf("film");
        Point cursor = new Point(0, innerStart + 1);

        var loc = locateAt(source, cursor);

        assertThat(loc.leafNode().getType()).isEqualTo("string_value");
        var range = CompletionContext.from(loc, source.getBytes(StandardCharsets.UTF_8)).replaceRange();
        assertThat(range).isEqualTo(new Range(
            new Position(0, innerStart),
            new Position(0, innerStart + "film".length())));
    }

    @Test
    void emptyBlockStringLiteral_replaceRangeIsZeroWidthAtInnerCursor() {
        String source = "type Foo @table(name: \"\"\"\"\"\") { x: Int }\n";
        int innerCol = source.indexOf("\"\"\"\"\"\"") + 3;
        Point cursor = new Point(0, innerCol);

        var loc = locateAt(source, cursor);
        var range = CompletionContext.from(loc, source.getBytes(StandardCharsets.UTF_8)).replaceRange();

        assertThat(range.getStart()).isEqualTo(new Position(0, innerCol));
        assertThat(range.getEnd()).isEqualTo(new Position(0, innerCol));
    }

    @Test
    void nestedInputFieldLeaf_coordinateKeyedByInputType() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.X\", method: \"foo\"}) }\n";
        Point cursor = new Point(0, source.indexOf("com.example") + 3);

        var loc = locateAt(source, cursor);

        assertThat(loc.coordinate()).isEqualTo(
            new SchemaCoordinate.InputField("ExternalCodeReference", "className"));
        assertThat(loc.leafNode().getType()).isEqualTo("string_value");
    }

    @Test
    void cursorOnArgKey_returnsEmpty() {
        String source = "type Foo @table(name: \"film\") { x: Int }\n";
        // Cursor on the 'n' of `name:`.
        Point cursor = new Point(0, source.indexOf("name") + 1);

        assertThat(VOCAB.locateAt(directiveOf(source, cursor), cursor, source.getBytes(StandardCharsets.UTF_8)))
            .isEmpty();
    }

    @Test
    void cursorOnUnknownDirective_returnsEmpty() {
        // Parses (one well-formed arg keeps the parens claimed) but the
        // directive name is not in the registry.
        String source = "type Foo @notADirective(name: \"x\") { bar: Int }\n";
        Point cursor = new Point(0, source.indexOf("\"x\"") + 1);

        assertThat(VOCAB.locateAt(directiveOf(source, cursor), cursor, source.getBytes(StandardCharsets.UTF_8)))
            .isEmpty();
    }

    @Test
    void cursorInsideObjectValueButOutsideAnyField_returnsEmpty() {
        // @reference(path: [{|}]) — cursor inside the empty object_value.
        // No leaf value/identifier under cursor.
        String source = "type Foo @table(name: \"film\") { bar: Int @reference(path: [{}]) }\n";
        Point cursor = new Point(0, source.indexOf("{}") + 1);

        assertThat(VOCAB.locateAt(directiveOf(source, cursor), cursor, source.getBytes(StandardCharsets.UTF_8)))
            .isEmpty();
    }

    @Test
    void coordinateAt_isThinWrapperOverLocateAt() {
        String source = "type Foo @table(name: \"film\") { x: Int }\n";
        Point cursor = new Point(0, source.indexOf("film") + 1);
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var directive = directiveOf(source, cursor);

        assertThat(VOCAB.coordinateAt(directive, cursor, bytes))
            .contains(new SchemaCoordinate.DirectiveArg("table", "name"));

        // And empty in cases locateAt is empty.
        Point onKey = new Point(0, source.indexOf("name") + 1);
        assertThat(VOCAB.coordinateAt(directiveOf(source, onKey), onKey, bytes)).isEmpty();
    }

    private static LspVocabulary.CursorLocation locateAt(String source, Point cursor) {
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        return VOCAB.locateAt(directiveOf(source, cursor), cursor, bytes)
            .orElseThrow(() -> new AssertionError("expected locateAt to find a leaf at " + cursor));
    }

    private static Directives.Directive directiveOf(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var tree = parser.parse(source).orElseThrow();
        return Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at " + cursor));
    }
}
