package no.sikt.graphitron.lsp;

import org.junit.jupiter.api.Test;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import io.github.treesitter.jtreesitter.Language;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that jtreesitter loads the graphitron-tree-sitter-natives grammar
 * binary against an OS-installed libtree-sitter and that the
 * tree-sitter-graphql grammar parses a trivial schema. If this test fails on
 * a platform, either the natives jar does not cover that platform or the
 * system libtree-sitter is missing on the test runner.
 */
class TreeSitterSmokeTest {

    @Test
    void parsesMinimalSchema() {
        var parser = new Parser();
        parser.setLanguage(no.sikt.graphitron.lsp.parsing.GraphqlLanguage.get());

        var source = "type Foo { bar: Int }";
        Tree tree = parser.parse(source).orElseThrow();

        assertThat(tree).isNotNull();
        assertThat(tree.getRootNode().hasError()).isFalse();
        assertThat(tree.getRootNode().getType()).isEqualTo("source_file");
    }
}
