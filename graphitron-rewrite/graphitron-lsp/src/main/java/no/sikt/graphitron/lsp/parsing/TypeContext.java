package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSNode;

import java.util.Optional;
import java.util.Set;

/**
 * Resolves the surrounding GraphQL-type-definition context for a node.
 * Per-directive completion providers use this to figure out which
 * {@code @table}-bound type a {@code @field(name:)} or
 * {@code @reference(...)} sits inside, so column / FK suggestions can be
 * filtered by the relevant table.
 *
 * <p>The walk goes parent-by-parent until it hits a type-definition node;
 * tree-sitter-graphql produces one of {@code object_type_definition} /
 * {@code interface_type_definition} / {@code input_object_type_definition}
 * for the three places {@code @table} can be applied.
 */
public final class TypeContext {

    private static final Set<String> TYPE_DEFINITION_KINDS = Set.of(
        "object_type_definition",
        "interface_type_definition",
        "input_object_type_definition"
    );

    private TypeContext() {}

    /**
     * Walks ancestors of {@code inner} until a type-definition node is found.
     * Returns empty if {@code inner} is at the top level (e.g. the cursor sits
     * on a directive applied to a schema-level element with no enclosing type).
     */
    public static Optional<TSNode> enclosingTypeDefinition(TSNode inner) {
        TSNode node = inner;
        while (node != null && !node.isNull()) {
            if (TYPE_DEFINITION_KINDS.contains(node.getType())) {
                return Optional.of(node);
            }
            TSNode parent = node.getParent();
            if (parent == null || parent.isNull() || parent.equals(node)) {
                return Optional.empty();
            }
            node = parent;
        }
        return Optional.empty();
    }

    /**
     * Returns the SQL table name from the {@code @table(name: "...")}
     * directive applied to {@code typeDef}, if any. Strips surrounding
     * quotes from the string literal. Only inspects directives directly
     * on {@code typeDef} (not directives on nested field definitions).
     */
    public static Optional<String> tableNameOf(TSNode typeDef, byte[] source) {
        TSNode directives = childOfKind(typeDef, "directives");
        if (directives == null) return Optional.empty();
        for (int i = 0; i < directives.getChildCount(); i++) {
            TSNode child = directives.getChild(i);
            if (!"directive".equals(child.getType())) continue;
            TSNode nameNode = childOfKind(child, "name");
            if (nameNode == null || !"table".equals(Nodes.text(nameNode, source))) continue;
            String value = stringArg(child, "name", source);
            if (value != null) return Optional.of(value);
        }
        return Optional.empty();
    }

    /**
     * Reads the string-literal value of {@code argName} from a {@code directive}
     * node. Returns {@code null} when the argument is absent or its value is
     * not a string literal.
     */
    public static String stringArg(TSNode directive, String argName, byte[] source) {
        TSNode arguments = childOfKind(directive, "arguments");
        if (arguments == null) return null;
        for (int i = 0; i < arguments.getChildCount(); i++) {
            TSNode argNode = arguments.getChild(i);
            if (!"argument".equals(argNode.getType())) continue;
            TSNode keyNode = childOfKind(argNode, "name");
            if (keyNode == null || !argName.equals(Nodes.text(keyNode, source))) continue;
            TSNode valueNode = childOfKind(argNode, "value");
            if (valueNode == null) return null;
            return Nodes.unquote(Nodes.text(valueNode, source));
        }
        return null;
    }

    private static TSNode childOfKind(TSNode parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            TSNode child = parent.getChild(i);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
