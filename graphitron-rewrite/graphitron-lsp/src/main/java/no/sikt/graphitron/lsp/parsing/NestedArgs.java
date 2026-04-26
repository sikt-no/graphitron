package no.sikt.graphitron.lsp.parsing;

import org.treesitter.TSNode;
import org.treesitter.TSPoint;

import java.util.Optional;

/**
 * Resolves nested {@code key: value} positions inside a directive
 * argument. Used by per-directive completion providers whose argument
 * is structured (an object literal or a list of object literals)
 * rather than a flat string value.
 *
 * <p>Example: {@code @reference(path: [{key: "fk_name"}])}. The cursor
 * sits inside the nested {@code key} object-field's value; the outer
 * argument is {@code path}. {@link #findContaining} returns both,
 * letting the completion provider know which directive arg it is in
 * and which nested field carries the cursor.
 *
 * <p>Tree-sitter-graphql parses nested values as
 * {@code value -> list_value -> value -> object_value -> object_field}.
 * The walk descends through whatever wrappers exist and reports the
 * innermost matching object_field.
 */
public final class NestedArgs {

    private NestedArgs() {}

    public record Nested(
        Directives.Argument outerArgument,
        String outerArgumentName,
        TSNode nestedField,
        TSNode nestedFieldName,
        TSNode nestedValue,
        String nestedFieldNameText
    ) {}

    /**
     * Returns the innermost {@code object_field} containing {@code pos}
     * within {@code directive}'s arguments, paired with the top-level
     * argument it ultimately lives under. Empty if {@code pos} is not
     * inside any nested object field (e.g. it is on a flat string arg
     * or outside the argument list entirely).
     */
    public static Optional<Nested> findContaining(
        Directives.Directive directive,
        TSPoint pos,
        byte[] source
    ) {
        for (Directives.Argument arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            TSNode innerObjectField = findInnermostObjectField(arg.value(), pos);
            if (innerObjectField == null) continue;
            TSNode nameNode = childOfKind(innerObjectField, "name");
            TSNode valueNode = childOfKind(innerObjectField, "value");
            if (nameNode == null || valueNode == null) continue;
            return Optional.of(new Nested(
                arg,
                Nodes.text(arg.key(), source),
                innerObjectField,
                nameNode,
                valueNode,
                Nodes.text(nameNode, source)
            ));
        }
        return Optional.empty();
    }

    /**
     * Recursive descent through value-wrappers ({@code list_value},
     * {@code object_value}, plain {@code value}) looking for the
     * deepest {@code object_field} that contains {@code pos}.
     */
    private static TSNode findInnermostObjectField(TSNode node, TSPoint pos) {
        if (node == null || node.isNull() || !Nodes.contains(node, pos)) {
            return null;
        }
        TSNode best = null;
        if ("object_field".equals(node.getType())) {
            best = node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            TSNode descendant = findInnermostObjectField(node.getChild(i), pos);
            if (descendant != null) {
                best = descendant;
            }
        }
        return best;
    }

    private static TSNode childOfKind(TSNode parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            TSNode child = parent.getChild(i);
            if (kind.equals(child.getType())) return child;
        }
        return null;
    }
}
