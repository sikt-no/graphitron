package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.ArgNameCompletions;
import no.sikt.graphitron.lsp.completions.ClassNameCompletions;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.completions.MethodCompletions;
import no.sikt.graphitron.lsp.completions.NodeTypeCompletions;
import no.sikt.graphitron.lsp.completions.ReferenceCompletions;
import no.sikt.graphitron.lsp.completions.ScalarTypeCompletions;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-format invariant for every completion provider: each
 * {@link CompletionItem} carries an explicit {@link TextEdit} whose
 * {@link Range} covers the whole value (or partial value) at the cursor.
 * Without this the LSP client falls back to its own word-boundary
 * heuristics; eglot's GraphQL-mode syntax table does not include
 * {@code .} as a symbol constituent, so a dotted candidate like
 * {@code com.example.FilmService} gets concatenated with the partial
 * prefix the user already typed.
 *
 * <p>One regression pin per provider (eight value sites + ArgName), plus
 * empty-literal and block-string corner cases. Adding a new completion
 * provider should add a row here rather than copying the assertion shape.
 */
class CompletionTextEditTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    @Test
    void classNameItem_textEditCoversFullDottedValue() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmServ\", method: \"foo\"}) }\n";
        int innerStart = source.indexOf("com.example.FilmServ");
        // Cursor mid-value (between FilmS and erv): non-trivial prefix.
        Point cursor = new Point(0, innerStart + "com.example.FilmS".length());

        var items = runClassName(source, cursor, fqn("com.example.FilmService"));

        assertTextEditRange(items, "com.example.FilmService",
            new Range(new Position(0, innerStart), new Position(0, innerStart + "com.example.FilmServ".length())));
    }

    @Test
    void methodItem_textEditCoversFullDottedValue() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"li\"}) }\n";
        int methodStart = source.indexOf("\"li\"") + 1;
        Point cursor = new Point(0, methodStart + 1);

        var items = runMethod(source, cursor,
            new CompletionData(List.of(), List.of(), List.of(
                new CompletionData.ExternalReference("com.example.FilmService", "com.example.FilmService", "",
                    List.of(new CompletionData.Method("list", "List", "", List.of())), List.of()))));

        assertTextEditRange(items, "list",
            new Range(new Position(0, methodStart), new Position(0, methodStart + "li".length())));
    }

    @Test
    void tableItem_textEditCoversFullValue() {
        String source = "type Foo @table(name: \"fi\") { x: Int }\n";
        int innerStart = source.indexOf("\"fi\"") + 1;
        Point cursor = new Point(0, innerStart + 1);

        var data = new CompletionData(
            List.of(new CompletionData.Table("film", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of())),
            List.of(), List.of());
        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> TableCompletions.generate(VOCAB, data, ctx));

        assertTextEditRange(items, "film",
            new Range(new Position(0, innerStart), new Position(0, innerStart + "fi".length())));
    }

    @Test
    void fieldItem_textEditCoversFullValue() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @field(name: "ti")
            }
            """;
        int line = 1;
        var lines = source.split("\n");
        int innerStart = lines[line].indexOf("\"ti\"") + 1;
        Point cursor = new Point(line, innerStart + 1);

        var data = new CompletionData(
            List.of(new CompletionData.Table("film", "", CompletionData.SourceLocation.UNKNOWN,
                List.of(CompletionData.Column.of("title", "String", false, "")),
                List.of())),
            List.of(), List.of());
        var snapshot = new no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot.Built.Current(
            List.of(),
            java.util.Map.of("Foo", new no.sikt.graphitron.rewrite.catalog.TypeBackingShape.TableBacking("film"))
        );
        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> FieldCompletions.generate(VOCAB, data, snapshot, ctx, dir, bytes));

        assertTextEditRange(items, "title",
            new Range(new Position(line, innerStart), new Position(line, innerStart + "ti".length())));
    }

    @Test
    void referenceItem_textEditCoversFullValue() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "FILM__"}])
            }
            """;
        int line = 1;
        var lines = source.split("\n");
        int innerStart = lines[line].indexOf("\"FILM__\"") + 1;
        Point cursor = new Point(line, innerStart + 2);

        var film = new CompletionData.Table("film", "", CompletionData.SourceLocation.UNKNOWN, List.of(),
            List.of(CompletionData.Reference.of("language", "FILM__FILM_LANGUAGE_ID_FKEY", false)));
        var language = new CompletionData.Table("language", "", CompletionData.SourceLocation.UNKNOWN, List.of(), List.of());
        var data = new CompletionData(List.of(film, language), List.of(), List.of());

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ReferenceCompletions.generate(VOCAB, data, ctx, dir, bytes));

        assertTextEditRange(items, "FILM__FILM_LANGUAGE_ID_FKEY",
            new Range(new Position(line, innerStart), new Position(line, innerStart + "FILM__".length())));
    }

    @Test
    void scalarTypeItem_textEditCoversFullDottedValue() {
        String source = "scalar DateTime @scalarType(scalar: \"graphql.scalars.\")\n";
        int innerStart = source.indexOf("\"graphql.scalars.\"") + 1;
        Point cursor = new Point(0, innerStart + "graphql.scalars".length());

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ScalarTypeCompletions.generate(VOCAB, CompletionData.empty(), ctx, dir, bytes));

        // Convention table has DateTime → graphql.scalars.ExtendedScalars.DateTime.
        assertTextEditRange(items, "graphql.scalars.ExtendedScalars.DateTime",
            new Range(new Position(0, innerStart), new Position(0, innerStart + "graphql.scalars.".length())));
    }

    @Test
    void nodeTypeItem_textEditCoversFullValue() {
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "Fi")): Int
            }
            """;
        int line = 1;
        var lines = source.split("\n");
        int innerStart = lines[line].indexOf("\"Fi\"") + 1;
        Point cursor = new Point(line, innerStart + 1);

        var data = new CompletionData(List.of(), List.of(), List.of(), Map.of(),
            Map.of("Film", new CompletionData.NodeMetadata("Film", List.of("film_id"))));
        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> NodeTypeCompletions.generate(VOCAB, data, ctx));

        assertTextEditRange(items, "Film",
            new Range(new Position(line, innerStart), new Position(line, innerStart + "Fi".length())));
    }

    @Test
    void argNameItem_textEditCoversPartialIdentifier() {
        // Cursor lands on the arg-name identifier (the key side of a
        // top-level arg). The arg has a value (so tree-sitter claims the
        // outer parens), but the cursor is in the key span, not the
        // value. locateAt returns empty (key side); ArgNameCompletions
        // fires with a range covering the full identifier under cursor.
        String source = "type Foo @table(name: \"x\") { bar: Int }\n";
        int nameStart = source.indexOf("name");
        Point cursor = new Point(0, nameStart + 2);
        Position lspPos = new Position(0, nameStart + 2);

        var items = runArgName(source, cursor, lspPos, LspSchemaSnapshot.unavailable());

        assertTextEditRange(items, "name",
            new Range(new Position(0, nameStart), new Position(0, nameStart + "name".length())));
    }

    @Test
    void argNameItem_zeroWidthRangeWhenNoPartialIdentifier() {
        // @service(service: {...}, <cursor>) — after the comma, on whitespace.
        // No identifier under cursor; range collapses to zero-width at lspPos.
        String source = "type Foo { bar: Int @service(service: {className: \"x\"}, ) }\n";
        int col = source.indexOf(", )") + 2;
        Point cursor = new Point(0, col);
        Position lspPos = new Position(0, col);

        var items = runArgName(source, cursor, lspPos, LspSchemaSnapshot.unavailable());

        assertThat(items).isNotEmpty();
        for (var item : items) {
            assertThat(item.getTextEdit().getLeft().getRange())
                .isEqualTo(new Range(lspPos, lspPos));
        }
    }

    @Test
    void emptyStringLiteralRow_classNameItemRangeIsZeroWidthAtInnerCursor() {
        String source = "type Query { x: Int @service(service: {className: \"\", method: \"foo\"}) }\n";
        int innerCol = source.indexOf("\"\"") + 1;
        Point cursor = new Point(0, innerCol);

        var items = runClassName(source, cursor, fqn("com.example.FilmService"));

        Range expected = new Range(new Position(0, innerCol), new Position(0, innerCol));
        assertTextEditRange(items, "com.example.FilmService", expected);
    }

    @Test
    void blockStringRow_classNameItemRangeIsTripleQuoteStrippedInnerSpan() {
        String source = "type Query { x: Int @service(service: {className: \"\"\"com.example.\"\"\", method: \"foo\"}) }\n";
        int innerStart = source.indexOf("com.example.");
        Point cursor = new Point(0, innerStart + "com.example.".length());

        var items = runClassName(source, cursor, fqn("com.example.FilmService"));

        assertTextEditRange(items, "com.example.FilmService",
            new Range(new Position(0, innerStart), new Position(0, innerStart + "com.example.".length())));
    }

    @Test
    void cursorOnOpeningQuote_resolvesToInnerContentRange() {
        // Boundary case: Nodes.contains is inclusive at the endpoints; cursor
        // on the opening quote still resolves to the string_value leaf, and
        // its replace range is the inner content (zero-width here for the
        // empty literal).
        String source = "type Query { x: Int @service(service: {className: \"\", method: \"foo\"}) }\n";
        int openQuote = source.indexOf("\"\"");
        Point cursor = new Point(0, openQuote);

        var items = runClassName(source, cursor, fqn("com.example.FilmService"));

        Range expected = new Range(new Position(0, openQuote + 1), new Position(0, openQuote + 1));
        assertTextEditRange(items, "com.example.FilmService", expected);
    }

    // ---- Helpers ----

    private interface ValueProviderInvocation {
        List<CompletionItem> run(CompletionContext context, Directives.Directive directive, byte[] bytes);
    }

    private static List<CompletionItem> runValueProvider(String source, Point cursor, ValueProviderInvocation call) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at " + cursor));
        var loc = VOCAB.locateAt(directive, cursor, bytes)
            .orElseThrow(() -> new AssertionError("expected locateAt to land on a leaf at " + cursor));
        var context = CompletionContext.from(loc, bytes);
        return call.run(context, directive, bytes);
    }

    private static List<CompletionItem> runClassName(String source, Point cursor, CompletionData.ExternalReference ref) {
        var data = new CompletionData(List.of(), List.of(), List.of(ref));
        return runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ClassNameCompletions.generate(VOCAB, data, ctx));
    }

    private static List<CompletionItem> runMethod(String source, Point cursor, CompletionData data) {
        return runValueProvider(source, cursor,
            (ctx, dir, bytes) -> MethodCompletions.generate(VOCAB, data, ctx, dir, cursor, bytes));
    }

    private static List<CompletionItem> runArgName(
        String source, Point cursor, Position lspPos, LspSchemaSnapshot snapshot
    ) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at " + cursor));
        return ArgNameCompletions.generate(VOCAB, snapshot, directive, cursor, lspPos, bytes);
    }

    private static CompletionData.ExternalReference fqn(String name) {
        return new CompletionData.ExternalReference(name, name, "", List.of(), List.of());
    }

    private static void assertTextEditRange(List<CompletionItem> items, String label, Range expected) {
        var item = items.stream().filter(i -> label.equals(i.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError(
                "expected item with label '" + label + "' in " + items.stream().map(CompletionItem::getLabel).toList()));
        var edit = item.getTextEdit();
        assertThat(edit).as("item '%s' must carry an explicit TextEdit", label).isNotNull();
        var textEdit = edit.getLeft();
        assertThat(textEdit).as("item '%s' TextEdit must be the plain-TextEdit variant", label).isNotNull();
        assertThat(textEdit.getRange()).as("item '%s' replace range", label).isEqualTo(expected);
        assertThat(textEdit.getNewText()).as("item '%s' new text", label).isEqualTo(label);
    }
}
