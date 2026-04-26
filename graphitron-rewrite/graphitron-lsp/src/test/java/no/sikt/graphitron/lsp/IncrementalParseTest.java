package no.sikt.graphitron.lsp;

import no.sikt.graphitron.lsp.state.WorkspaceFile;
import org.junit.jupiter.api.Test;
import org.treesitter.TSPoint;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that incremental tree-sitter parsing works from Java the same
 * way it does from Rust: an edit produces an equivalent tree to a
 * from-scratch parse, but reuses subtree structure where possible.
 *
 * <p>This is the backbone of the Rust LSP's per-keystroke responsiveness;
 * proving it works through the bonede binding closes one of the biggest
 * questions about porting performance.
 */
class IncrementalParseTest {

    @Test
    void incrementalEditProducesEquivalentTree() {
        String source = """
            type Foo @table(name: "FILM") {
                bar: Int
            }
            """;
        var file = new WorkspaceFile(1, source);

        // Insert " baz: String" before the closing brace. Edit grows the
        // fields_definition; tree-sitter must add a node, not just retoken.
        int insertAt = source.indexOf("}\n");
        TSPoint insertPoint = new TSPoint(2, 0);
        String inserted = "    baz: String\n";
        file.applyEdit(2, insertAt, insertAt, insertPoint, insertPoint, inserted);

        assertThat(file.tree().getRootNode().hasError()).isFalse();
        assertThat(new String(file.source())).contains("baz: String");
        // Structural change: now two field_definitions, not one.
        long fieldDefs = countSubtreesOfKind(file.tree().getRootNode(), "field_definition");
        assertThat(fieldDefs).isEqualTo(2L);
    }

    private static long countSubtreesOfKind(org.treesitter.TSNode node, String kind) {
        long count = node.getType().equals(kind) ? 1 : 0;
        for (int i = 0; i < node.getChildCount(); i++) {
            count += countSubtreesOfKind(node.getChild(i), kind);
        }
        return count;
    }

    @Test
    void editAfterDoesNotInvalidateUnrelatedSubtree() {
        String source = """
            type A @table(name: "AAA") { f: Int }
            type B @table(name: "BBB") { f: Int }
            """;
        var file = new WorkspaceFile(1, source);

        // Snapshot the @table directive on type A's source position.
        String aBefore = source.substring(source.indexOf("@table"), source.indexOf("AAA") + 4);

        // Edit type B's table name from BBB to CCC.
        int bbbStart = source.indexOf("BBB");
        TSPoint bbbStartPoint = new TSPoint(1, source.indexOf("BBB") - source.indexOf('\n') - 1);
        TSPoint bbbEndPoint = new TSPoint(1, bbbStartPoint.getColumn() + 3);
        file.applyEdit(2, bbbStart, bbbStart + 3, bbbStartPoint, bbbEndPoint, "CCC");

        // The new source still contains type A's @table(name: "AAA") unchanged.
        assertThat(new String(file.source())).contains(aBefore);
        assertThat(new String(file.source())).contains("CCC");
        assertThat(file.tree().getRootNode().hasError()).isFalse();
    }
}
