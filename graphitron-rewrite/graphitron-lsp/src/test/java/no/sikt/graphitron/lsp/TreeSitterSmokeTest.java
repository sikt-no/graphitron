package no.sikt.graphitron.lsp;

import org.junit.jupiter.api.Test;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterGraphql;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the bonede tree-sitter Java binding loads its native libraries
 * and that the tree-sitter-graphql grammar parses a trivial schema. If this
 * test fails on a platform, the binding's bundled natives don't cover that
 * platform and we need a different distribution strategy.
 */
class TreeSitterSmokeTest {

    @Test
    void parsesMinimalSchema() {
        var parser = new TSParser();
        parser.setLanguage(new TreeSitterGraphql());

        var source = "type Foo { bar: Int }";
        TSTree tree = parser.parseString(null, source);

        assertThat(tree).isNotNull();
        assertThat(tree.getRootNode().hasError()).isFalse();
        assertThat(tree.getRootNode().getType()).isEqualTo("source_file");
    }
}
