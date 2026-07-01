package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cursor-to-coordinate resolution for {@link LspVocabulary#coordinateAt}.
 * The primitive replaces the {@code innermostObjectField} +
 * directive-name-switch idiom that recurred across every consumer; these
 * tests pin its behavior across the directive-arg, single-level, and
 * multi-level nesting cases.
 */
class CoordinateAtTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void cursorOnFlatDirectiveArgValueReturnsDirectiveArgCoordinate() {
        String source = """
            type Foo @table(name: "FILM") { bar: Int }
            """;
        int col = source.indexOf("FILM") + 1;

        var coord = resolveCoordinate(source, new Point(0, col));

        assertThat(coord).isEqualTo(new SchemaCoordinate.DirectiveArg("table", "name"));
    }

    @Test
    void cursorOnSourceRowFlatArgReturnsDirectiveArgCoordinate() {
        // The R110 gap closes here: today's hand-coded DirectiveDefinitions
        // does not list @sourceRow; coordinateAt resolves it from the
        // parsed registry.
        String source = """
            type Foo {
                bar: Int @sourceRow(className: "com.example.Lifter", method: "lift")
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("com") + 1;

        var coord = resolveCoordinate(source, new Point(line, col));

        assertThat(coord).isEqualTo(new SchemaCoordinate.DirectiveArg("sourceRow", "className"));
    }

    @Test
    void cursorOnSingleLevelNestedFieldReturnsInputFieldCoordinate() {
        // @service(service: { className: "..." }) — cursor inside className value.
        String source = """
            type Foo {
                bar: Int @service(service: {className: ""})
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("\"\"") + 1;

        var coord = resolveCoordinate(source, new Point(line, col));

        assertThat(coord).isEqualTo(
            new SchemaCoordinate.InputField("ExternalCodeReference", "className"));
    }

    @Test
    void cursorOnReferenceListElementFieldReturnsInputFieldCoordinate() {
        // @reference(path: [{table: "FILM"}]) — cursor inside table value.
        String source = """
            type Foo {
                bar: Int @reference(path: [{table: "FILM"}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("FILM") + 1;

        var coord = resolveCoordinate(source, new Point(line, col));

        assertThat(coord).isEqualTo(
            new SchemaCoordinate.InputField("ReferenceElement", "table"));
    }

    @Test
    void cursorOnDoublyNestedFieldDescendsIntoEcrInputType() {
        // @reference(path: [{condition: {className: "..."}}]) — cursor on
        // className. Two-level descent: ReferenceElement → ExternalCodeReference.
        String source = """
            type Foo {
                bar: Int @reference(path: [{condition: {className: ""}}])
            }
            """;
        var lines = source.split("\n");
        int line = 1;
        int col = lines[line].indexOf("\"\"") + 1;

        var coord = resolveCoordinate(source, new Point(line, col));

        assertThat(coord).isEqualTo(
            new SchemaCoordinate.InputField("ExternalCodeReference", "className"));
    }

    @Test
    void cursorOnDirectiveNameOutsideArgsReturnsEmpty() {
        // Cursor on the directive name (not inside any argument).
        String source = """
            type Foo @table(name: "FILM") { bar: Int }
            """;
        int directiveStart = source.indexOf("@table");
        int cursorCol = directiveStart + 2;

        // Find the directive by aiming the lookup at a column inside its
        // argument list, then run coordinateAt with the cursor on the
        // directive name itself.
        int argCol = source.indexOf("FILM");
        var directive = parseAndFindDirective(source, new Point(0, argCol));
        var bytes = source.getBytes(StandardCharsets.UTF_8);

        var coord = VOCAB.coordinateAt(directive, new Point(0, cursorCol), bytes);

        assertThat(coord).isEmpty();
    }

    @Test
    void cursorOnUnknownDirectiveReturnsEmpty() {
        String source = """
            type Foo @notADirective(name: "x") { bar: Int }
            """;
        int col = source.indexOf("\"x\"") + 1;
        var directive = parseAndFindDirective(source, new Point(0, col));
        var bytes = source.getBytes(StandardCharsets.UTF_8);

        var coord = VOCAB.coordinateAt(directive, new Point(0, col), bytes);

        assertThat(coord).isEmpty();
    }

    private static SchemaCoordinate resolveCoordinate(String source, Point cursor) {
        var directive = parseAndFindDirective(source, cursor);
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        return VOCAB.coordinateAt(directive, cursor, bytes)
            .orElseThrow(() -> new AssertionError("expected coordinate at cursor " + cursor));
    }

    private static Directives.Directive parseAndFindDirective(String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var tree = parser.parse(source).orElseThrow();
        return Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor " + cursor));
    }
}
