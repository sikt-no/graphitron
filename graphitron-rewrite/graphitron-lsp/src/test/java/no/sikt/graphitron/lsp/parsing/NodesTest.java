package no.sikt.graphitron.lsp.parsing;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Parser;
import io.github.treesitter.jtreesitter.Tree;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.FIELDS_DEFINITION;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * R347 (Slice 1) — pins the contract of the consolidated tree-sitter navigation helpers on
 * {@link Nodes}, the single home the 12 per-feature {@code childOfKind} copies collapsed into.
 *
 * <p>The two copies in {@code Definitions} and {@code TypeContext} had dropped the child null-guard
 * and could NPE; {@code TypeContext.stringArg} additionally dereferenced an unguarded child slot.
 * The shared helper is null-safe on the parent and on every child slot, so those latent NPEs cannot
 * recur. These tests are the explicit regression oracle for that null-safety, alongside the
 * existing feature suites that exercise the helpers end-to-end.
 */
class NodesTest {

    private static Tree parse(String source) {
        var parser = new Parser();
        parser.setLanguage(GraphqlLanguage.get());
        return parser.parse(source).orElseThrow();
    }

    private static Node firstOfType(Node node, String type) {
        if (type.equals(node.getType())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            Node child = node.getChild(i).orElse(null);
            if (child == null) {
                continue;
            }
            Node found = firstOfType(child, type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Test
    void childOfKindReturnsTheMatchingChild() {
        String source = "type Foo { bar: Int }";
        Node objectType = firstOfType(parse(source).getRootNode(), "object_type_definition");
        assertThat(objectType).isNotNull();

        Node name = Nodes.childOfKind(objectType, NAME);
        assertThat(name).isNotNull();
        assertThat(Nodes.text(name, source.getBytes(StandardCharsets.UTF_8))).isEqualTo("Foo");

        // The object type has a fields_definition child but no direct `value` child.
        assertThat(Nodes.childOfKind(objectType, FIELDS_DEFINITION)).isNotNull();
        assertThat(Nodes.childOfKind(objectType, VALUE)).isNull();
    }

    @Test
    void childOfKindIsNullSafeOnTheParent() {
        // The two pre-consolidation copies that dropped the null-guard would NPE here; the shared
        // helper returns null instead.
        assertThat(Nodes.childOfKind(null, NAME)).isNull();
    }

    @Test
    void matchesIsNullSafeAndKindAccurate() {
        Node objectType = firstOfType(parse("type Foo { bar: Int }").getRootNode(), "object_type_definition");
        Node name = Nodes.childOfKind(objectType, NAME);

        assertThat(NAME.matches(name)).isTrue();
        assertThat(VALUE.matches(name)).isFalse();
        assertThat(NAME.matches(null)).isFalse();
        assertThat(NAME.id()).isEqualTo("name");
    }

    @Test
    void sameNodeComparesByByteRangeAndType() {
        Tree tree = parse("type Foo { bar: Int }");
        Node objectType = firstOfType(tree.getRootNode(), "object_type_definition");

        // A name handle fetched twice via separate walks is range/type-equal but need not be
        // reference-equal; sameNode pins the value-identity contract callers rely on.
        Node nameA = Nodes.childOfKind(objectType, NAME);
        Node nameB = firstOfType(tree.getRootNode(), "name");
        assertThat(Nodes.sameNode(nameA, nameB)).isTrue();

        assertThat(Nodes.sameNode(nameA, objectType)).isFalse();
        assertThat(Nodes.nodeContains(objectType, nameA)).isTrue();
        assertThat(Nodes.nodeContains(nameA, objectType)).isFalse();
    }
}
