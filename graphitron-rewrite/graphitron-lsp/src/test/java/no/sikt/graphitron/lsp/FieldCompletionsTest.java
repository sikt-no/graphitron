package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.FieldCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import io.github.treesitter.jtreesitter.Language;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for {@code @field(name: "...")} column autocomplete.
 * The interesting bit is the context resolution: the LSP must walk up
 * from the field's directive to the enclosing type's {@code @table} to
 * pick which table's columns to suggest.
 */
class FieldCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

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
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("FILM_ID", "TITLE", "LANGUAGE_ID");
    }

    @Test
    void cursorOnFieldDirectiveWithoutEnclosingTableReturnsEmpty() {
        // Type has no @table directive, so the classifier projected
        // NoBacking; completions silence.
        String source = """
            type Foo {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("Foo", new TypeBackingShape.NoBacking.UnbackedResult())
        );
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unknownTableReturnsEmpty() {
        // Enclosing type points at a table the catalog does not know — but
        // the classifier still projected TableBacking(MISSING). The
        // completion arm consults CompletionData.getTable which returns
        // empty; no candidates surface.
        String source = """
            type Foo @table(name: "MISSING") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "MISSING"), source, cursor);

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
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void interfaceTypeWithTableDirectiveAlsoResolvesColumns() {
        // @table on an interface — TableInterfaceType projects to
        // TableBacking, same data path as TableType.
        String source = """
            interface Movie @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Movie", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .contains("FILM_ID", "TITLE");
    }

    /**
     * R100 — {@code @node(keyColumns:)} reuses
     * {@link no.sikt.graphitron.lsp.parsing.Behavior.CatalogColumnBinding}
     * via an overlay-delta entry. The candidate set is the columns of the
     * enclosing type's {@code @table}; cursor inside a list-element string
     * literal completes the same way a flat string-valued column slot does.
     */
    @Test
    void nodeKeyColumnsCompletionInsideListLiteralReturnsTableColumns() {
        String source = """
            type Foo implements Node @table(name: "FILM") @node(keyColumns: [""]) {
                id: ID
            }
            """;
        // Cursor inside the empty quoted element of the list.
        int line = 0;
        int col = source.split("\n")[line].indexOf("[\"") + 2;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), tableSnapshot("Foo", "FILM"), source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("FILM_ID", "TITLE", "LANGUAGE_ID");
    }

    @Test
    void recordBackingCompletionReturnsRecordComponents() {
        String source = """
            input FilmInput @record(record: {className: "com.example.FilmDto"}) {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmInput", new TypeBackingShape.RecordBacking("com.example.FilmDto", List.of(
                new TypeBackingShape.MemberSlot("filmId", "Integer"),
                new TypeBackingShape.MemberSlot("title", "String")
            )))
        );
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void pojoBackingCompletionReturnsBeanAccessors() {
        String source = """
            type FilmPojo @record(record: {className: "com.example.FilmPojo"}) {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of("FilmPojo", new TypeBackingShape.PojoBacking("com.example.FilmPojo", List.of(
                new TypeBackingShape.MemberSlot("filmId", "Integer"),
                new TypeBackingShape.MemberSlot("title", "String")
            )))
        );
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).extracting(c -> c.getLabel())
            .containsExactly("filmId", "title");
    }

    @Test
    void snapshotMissReturnsEmpty() {
        // SDL declares the type but the snapshot has no entry — same as
        // mid-edit state. Silent rather than spamming candidates.
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var snapshot = new LspSchemaSnapshot.Built.Current(List.of(), Map.of());
        var items = run(filmCatalog(), snapshot, source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void unavailableSnapshotReturnsEmpty() {
        // Pre-build state — no classifier output to consult yet.
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int @field(name: "")
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf('"') + 1;
        Point cursor = new Point(line, col);

        var items = run(filmCatalog(), LspSchemaSnapshot.unavailable(), source, cursor);

        assertThat(items).isEmpty();
    }

    private static LspSchemaSnapshot tableSnapshot(String typeName, String tableName) {
        return new LspSchemaSnapshot.Built.Current(
            List.of(),
            Map.of(typeName, new TypeBackingShape.TableBacking(tableName))
        );
    }

    private static List<org.eclipse.lsp4j.CompletionItem> run(
        CompletionData data, LspSchemaSnapshot snapshot, String source, Point cursor
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
        return FieldCompletions.generate(VOCAB, data, snapshot, context, directive, bytes);
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
