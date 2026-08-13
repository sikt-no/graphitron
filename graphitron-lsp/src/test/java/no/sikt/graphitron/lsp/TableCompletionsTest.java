package no.sikt.graphitron.lsp;

import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.completions.CompletionContext;
import no.sikt.graphitron.lsp.completions.TableCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Table-name completion off the graph's catalog census. The candidates are {@code sql_table} rows
 * scoped to the document's graph, so what a popup can offer is what a capture wrote for that module
 * and nothing a sibling module wrote.
 *
 * <p>The census is captured from the fixture module's real generated jOOQ model, which is what makes
 * the class FQN the description arm joins on a real one.
 */
class TableCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

    private static final String SDL = "type Query { placeholder: Int }\n";

    @Test
    void tableNameCompletionReturnsTheCatalogsTables(@TempDir Path tmp) {
        // Cursor sits inside the empty quoted string after `name: `.
        String source = """
            type Foo @table(name: "") {
                bar: Int
            }
            """;
        Point cursor = new Point(0, source.indexOf('"') + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(labels(fixture.handle(), source, cursor))
                .contains("film", "actor", "language")
                // Ordered by name, which the census can state and the incumbent projection could
                // not: its order was the generated Tables class's field order.
                .containsSubsequence("actor", "film", "language");
        }
    }

    @Test
    void referenceElementTableNestedFieldAlsoCompletesCatalogTables(@TempDir Path tmp) {
        // ReferenceElement.table is a CatalogTableBinding in the canonical overlay. The same
        // provider that fires on @table(name:) also fires on @reference(path: [{table:}]) — the
        // directive-name switch dropped, dispatch goes through the coordinate's behavior arm.
        String source = """
            type Foo {
                bar: Int @reference(path: [{table: ""}])
            }
            """;
        int line = 1;
        Point cursor = new Point(line, source.split("\n")[line].indexOf("\"\"") + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(labels(fixture.handle(), source, cursor)).contains("film", "language");
        }
    }

    @Test
    void cursorOutsideArgumentReturnsEmpty(@TempDir Path tmp) {
        String source = """
            type Foo @table(name: "film") {
                bar: Int
            }
            """;
        // Cursor on the directive name, not inside an argument.
        Point cursor = new Point(0, source.indexOf("@table") + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            assertThat(labels(fixture.handle(), source, cursor)).isEmpty();
        }
    }

    /**
     * The generated table class's Javadoc documents the candidate. It is the join the FQN capture
     * exists for: the class lives in the generated package, which the class census excludes by
     * design, so the description reaches the popup through {@code sql_table.class_fqn} meeting a
     * {@code java_class_declaration} row written on the source's own cadence.
     */
    @Test
    void theGeneratedClassJavadocDocumentsTheCandidate(@TempDir Path tmp) {
        String source = """
            type Foo @table(name: "") {
                bar: Int
            }
            """;
        Point cursor = new Point(0, source.indexOf('"') + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            var items = items(fixture.handle(), source, cursor);
            assertThat(documentationOf(items, "film"))
                .as("no source has been parsed yet, and the fixture database carries no comments")
                .isEmpty();

            fixture.withJavaSource(tmp.resolve("generated"), fixture.tableClassFqn("film"), """
                /** Movies the rental store carries. */
                public class Film {
                }
                """);

            assertThat(documentationOf(items(fixture.handle(), source, cursor), "film"))
                .isEqualTo("Movies the rental store carries.");
        }
    }

    /**
     * The census is the graph's, not the store's. A second graph in the same store that read no
     * catalog completes nothing here, which is the partition doing its job rather than a degraded
     * answer: absence of a census is an answer.
     */
    @Test
    void aGraphThatReadNoCatalogHasNoTablesToOffer(@TempDir Path tmp) {
        String source = """
            type Foo @table(name: "") {
                bar: Int
            }
            """;
        Point cursor = new Point(0, source.indexOf('"') + 1);

        try (var fixture = StoreFixture.ofCatalog(tmp, SDL)) {
            fixture.andGraph(tmp, "billing", "type Query { y: Int }\n", List.of());

            assertThat(labels(fixture.handle(), source, cursor)).contains("film");
            assertThat(labels(fixture.handleFor("billing"), source, cursor)).isEmpty();
        }
    }

    private static String documentationOf(List<CompletionItem> items, String label) {
        var item = items.stream().filter(i -> label.equals(i.getLabel())).findFirst()
            .orElseThrow(() -> new AssertionError("no candidate labelled " + label));
        var documentation = item.getDocumentation();
        return documentation == null ? "" : documentation.getRight().getValue();
    }

    private static List<String> labels(StoreHandle handle, String source, Point cursor) {
        return items(handle, source, cursor).stream().map(CompletionItem::getLabel).toList();
    }

    private static List<CompletionItem> items(StoreHandle handle, String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return VOCAB.locateAt(directive, cursor, bytes)
            .map(location -> TableCompletions.generate(
                VOCAB, handle, CompletionContext.from(location, bytes)))
            .orElseGet(List::of);
    }
}
