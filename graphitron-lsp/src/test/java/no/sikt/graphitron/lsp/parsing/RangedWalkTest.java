package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two walks a request over one region of a document makes, asked to stay inside it. An inlay
 * request is one editor window, reissued on every scroll, so a walk of the whole document per
 * request made a window of a large schema cost what the schema did; these pin that a walk over a
 * region visits the region and leaves the rest of the tree alone.
 */
class RangedWalkTest {

    private static final String SOURCE = """
        type Film @table(name: "film") {
            title: String @field(name: "title")
        }
        type Language @table(name: "language") {
            name: String @field(name: "name")
        }
        """;

    private static final byte[] BYTES = SOURCE.getBytes(StandardCharsets.UTF_8);

    @Test
    void aRefusedSubtreeIsNeitherVisitedNorEntered() {
        var root = parse().getRootNode();
        var tested = new ArrayList<Node>();
        var visited = new ArrayList<String>();
        DeclarationKind.walkAll(root, node -> {
            tested.add(node);
            return node.getEndPoint().row() >= 3;
        }, declaration -> visited.add(name(declaration)));

        assertThat(visited).containsExactly("Language");
        assertThat(tested.stream().filter(node -> node.getEndPoint().row() < 3).toList())
            .as("one node outside the region is asked about, the one the walk then stops at")
            .hasSize(1);
    }

    @Test
    void theUnrangedWalkVisitsEveryDeclaration() {
        var visited = new ArrayList<String>();
        DeclarationKind.walkAll(parse().getRootNode(), declaration -> visited.add(name(declaration)));
        assertThat(visited).containsExactly("Film", "Language");
    }

    @Test
    void aRowRangeFindsOnlyTheDirectivesOnThoseRows() {
        var root = parse().getRootNode();
        assertThat(directiveNames(Directives.findAll(root, 3, 4))).containsExactly("table", "field");
        assertThat(directiveNames(Directives.findAll(root, 1, 1))).containsExactly("field");
        assertThat(directiveNames(Directives.findAll(root))).containsExactly("table", "field", "table", "field");
    }

    private static List<String> directiveNames(List<Directives.Directive> directives) {
        return directives.stream().map(d -> Nodes.text(d.nameNode(), BYTES)).toList();
    }

    private static String name(Node declaration) {
        return Nodes.text(Nodes.childOfKind(declaration, GraphqlNodeKind.NAME), BYTES);
    }

    private static Tree parse() {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        return parser.parse(SOURCE).orElseThrow();
    }
}
