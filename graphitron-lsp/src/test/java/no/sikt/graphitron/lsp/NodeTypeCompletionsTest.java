package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.NodeTypeCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @nodeId(typeName: "...")} GraphQL-type-name
 * completion. The candidate set is {@code graphitron_node_entry}: every type whose SDL carries
 * {@code @node}, regardless of whether the author filled in
 * {@code typeId} or {@code keyColumns}.
 */
class NodeTypeCompletionsTest {

    private static final LspVocabulary VOCAB = BundledVocabulary.get();

    @TempDir
    static Path tmp;

    private static StoreFixture nodes;
    private static StoreFixture noNodes;

    @BeforeAll
    static void capture() {
        // One @node with both arguments filled in and one with neither: both are candidates, which is
        // what the relation says by keying on the type alone.
        nodes = StoreFixture.of(tmp, """
            type Query { x: Int }
            type Film @node(typeId: "Film", keyColumns: ["film_id"]) { id: ID }
            type Actor @node { id: ID }
            """);
        noNodes = StoreFixture.of(tmp, "unnoded", "type Query { x: Int }\n", List.of());
    }

    @AfterAll
    static void closeStores() {
        nodes.close();
        noNodes.close();
    }

    @Test
    void typeNameCompletionReturnsNodeBearingTypes() {
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(nodes.handle(), source, cursor);

        assertThat(items).extracting(CompletionItem::getLabel)
            .containsExactlyInAnyOrder("Film", "Actor");
    }

    @Test
    void cursorOutsideTypeNameArgReturnsEmpty() {
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "Film")): Int
            }
            """;
        // Cursor on the directive name token, not inside the arg value.
        int line = 1;
        int col = source.split("\n")[line].indexOf("@nodeId") + 1;
        Point cursor = new Point(line, col);

        var items = run(nodes.handle(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void noNodeDeclarationsReturnsEmptyList() {
        // A graph whose SDL declares no @node at all. The provider offers no candidates rather than
        // failing, which is also what a graph captured before the author wrote one looks like.
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(noNodes.handle(), source, cursor);

        assertThat(items).isEmpty();
    }

    private static List<CompletionItem> run(StoreHandle store, String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        var locOpt = VOCAB.locateAt(directive, cursor, bytes);
        if (locOpt.isEmpty()) return List.of();
        var context = no.sikt.graphitron.lsp.completions.CompletionContext.from(locOpt.get(), bytes);
        return NodeTypeCompletions.generate(VOCAB, store, context);
    }
}
