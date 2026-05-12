package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.completions.NodeTypeCompletions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.GraphqlLanguage;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Point;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@code @nodeId(typeName: "...")} GraphQL-type-name
 * completion. The candidate set is the keys of
 * {@link CompletionData#nodeMetadata()}: every type whose SDL carries
 * {@code @node}, regardless of whether the author filled in
 * {@code typeId} or {@code keyColumns}.
 */
class NodeTypeCompletionsTest {

    private static final LspVocabulary VOCAB = LspVocabulary.load();

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

        var items = run(catalog(), source, cursor);

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

        var items = run(catalog(), source, cursor);

        assertThat(items).isEmpty();
    }

    @Test
    void emptyNodeMetadataReturnsEmptyList() {
        // Pre-build state — no @node types observed yet. The completion
        // provider simply offers no candidates rather than failing.
        String source = """
            type Query {
                x(id: ID @nodeId(typeName: "")): Int
            }
            """;
        int line = 1;
        int col = source.split("\n")[line].indexOf("\"\"") + 1;
        Point cursor = new Point(line, col);

        var items = run(CompletionData.empty(), source, cursor);

        assertThat(items).isEmpty();
    }

    private static List<CompletionItem> run(CompletionData data, String source, Point cursor) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        var bytes = source.getBytes(StandardCharsets.UTF_8);
        var tree = parser.parse(source).orElseThrow();
        var directive = Directives.findContaining(tree.getRootNode(), cursor)
            .orElseThrow(() -> new AssertionError("expected directive at cursor"));
        return NodeTypeCompletions.generate(VOCAB, data, directive, cursor, bytes);
    }

    private static CompletionData catalog() {
        return new CompletionData(
            List.of(),
            List.of(),
            List.of(),
            Map.of(),
            Map.of(
                "Film", new CompletionData.NodeMetadata("Film", List.of("film_id")),
                "Actor", new CompletionData.NodeMetadata(null, null)
            )
        );
    }
}
