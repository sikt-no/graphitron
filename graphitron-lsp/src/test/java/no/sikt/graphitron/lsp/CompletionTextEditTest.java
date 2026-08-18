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
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

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

    @TempDir
    static Path tmp;

    /**
     * One capture for every store-backed arm below: a service class, a scalar holder, a @node type,
     * a table-bound type for the arms that read a binding, and the fixture module's catalog for the
     * table and column arms.
     */
    private static StoreFixture store;

    @BeforeAll
    static void capture() {
        store = StoreFixture.ofCatalog(tmp, """
            type Query { x: Int }
            type Film @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID }
            type Foo @table(name: "film") { bar: Int }
            """,
            List.of(
                StoreFixture.jarClass("com.example.FilmService",
                    List.of(StoreFixture.method("list", "List"))),
                StoreFixture.scalarHolder("graphql.scalars.ExtendedScalars", "DateTime")));
    }

    @AfterAll
    static void closeStore() {
        store.close();
    }

    @Test
    void classNameItem_textEditCoversFullDottedValue() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmServ\", method: \"foo\"}) }\n";
        int innerStart = source.indexOf("com.example.FilmServ");
        // Cursor mid-value (between FilmS and erv): non-trivial prefix.
        Point cursor = new Point(0, innerStart + "com.example.FilmS".length());

        var items = runClassName(source, cursor);

        assertTextEditRange(items, "com.example.FilmService",
            new Range(new Position(0, innerStart), new Position(0, innerStart + "com.example.FilmServ".length())));
    }

    @Test
    void methodItem_textEditCoversFullDottedValue() {
        String source = "type Query { x: Int @service(service: {className: \"com.example.FilmService\", method: \"li\"}) }\n";
        int methodStart = source.indexOf("\"li\"") + 1;
        Point cursor = new Point(0, methodStart + 1);

        var items = runMethod(source, cursor);

        assertTextEditRange(items, "list",
            new Range(new Position(0, methodStart), new Position(0, methodStart + "li".length())));
    }

    @Test
    void tableItem_textEditCoversFullValue() {
        String source = "type Foo @table(name: \"fi\") { x: Int }\n";
        int innerStart = source.indexOf("\"fi\"") + 1;
        Point cursor = new Point(0, innerStart + 1);

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> TableCompletions.generate(VOCAB, store.handle(), ctx));

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

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> FieldCompletions.generate(VOCAB, store.handle(), ctx, dir, bytes));

        // The candidate is the generated Java field name, which is what the census carries and what
        // an author types; the typed prefix it replaces is their own lowercase "ti".
        assertTextEditRange(items, "TITLE",
            new Range(new Position(line, innerStart), new Position(line, innerStart + "ti".length())));
    }

    @Test
    void referenceItem_textEditCoversFullValue() {
        String source = """
            type Foo @table(name: "film") {
                bar: Int @reference(path: [{key: "film_lang"}])
            }
            """;
        int line = 1;
        var lines = source.split("\n");
        int innerStart = lines[line].indexOf("\"film_lang\"") + 1;
        Point cursor = new Point(line, innerStart + 2);

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ReferenceCompletions.generate(
                VOCAB, store.handle(), ctx, dir, bytes));

        // The candidate is the SQL constraint name, the namespace key: resolves first and the manual
        // teaches; the edit still covers the whole partial value the author typed.
        assertTextEditRange(items, "film_language_id_fkey",
            new Range(new Position(line, innerStart), new Position(line, innerStart + "film_lang".length())));
    }

    @Test
    void scalarTypeItem_textEditCoversFullDottedValue() {
        String source = "scalar DateTime @scalarType(scalar: \"graphql.scalars.\")\n";
        int innerStart = source.indexOf("\"graphql.scalars.\"") + 1;
        Point cursor = new Point(0, innerStart + "graphql.scalars".length());

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ScalarTypeCompletions.generate(VOCAB, store.handle(), ctx, dir, bytes));

        // The scan carries DateTime on graphql.scalars.ExtendedScalars → composed FQN.
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

        var items = runValueProvider(source, cursor,
            (ctx, dir, bytes) -> NodeTypeCompletions.generate(VOCAB, store.handle(), ctx));

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

        var items = runArgName(source, cursor, lspPos);

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

        var items = runArgName(source, cursor, lspPos);

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

        var items = runClassName(source, cursor);

        Range expected = new Range(new Position(0, innerCol), new Position(0, innerCol));
        assertTextEditRange(items, "com.example.FilmService", expected);
    }

    @Test
    void blockStringRow_classNameItemRangeIsTripleQuoteStrippedInnerSpan() {
        String source = "type Query { x: Int @service(service: {className: \"\"\"com.example.\"\"\", method: \"foo\"}) }\n";
        int innerStart = source.indexOf("com.example.");
        Point cursor = new Point(0, innerStart + "com.example.".length());

        var items = runClassName(source, cursor);

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

        var items = runClassName(source, cursor);

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

    private static List<CompletionItem> runClassName(String source, Point cursor) {
        return runValueProvider(source, cursor,
            (ctx, dir, bytes) -> ClassNameCompletions.generate(VOCAB, store.handle(), ctx));
    }

    private static List<CompletionItem> runMethod(String source, Point cursor) {
        return runValueProvider(source, cursor,
            (ctx, dir, bytes) -> MethodCompletions.generate(VOCAB, store.handle(), ctx, dir, cursor, bytes));
    }

    private static List<CompletionItem> runArgName(String source, Point cursor, Position lspPos) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        byte[] bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at " + cursor));
        return ArgNameCompletions.generate(VOCAB, store.handle(), directive, cursor, lspPos, bytes);
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
