package no.sikt.graphitron.lsp.completions;

import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Argument-name completion driven off the parsed registry rather than a
 * hand-coded directive list. Two cursor cases:
 *
 * <ul>
 *   <li><b>Top-level.</b> Cursor inside a directive's argument list but
 *       outside any specific argument value (between args, on whitespace
 *       inside the parens). Completes the directive's argument names.</li>
 *   <li><b>Nested.</b> Cursor inside a nested {@code object_value} (the
 *       value side of an input-type-typed directive arg) but outside any
 *       specific {@code object_field}. Completes the input type's field
 *       names; descends through the registry's input-type field tree to
 *       resolve the right input type for the current nesting depth.</li>
 * </ul>
 *
 * <p>Either case requires the directive name to resolve in the registry;
 * unknown directives produce no completions (the unknown-directive
 * diagnostic surfaces the typo elsewhere).
 */
public final class ArgNameCompletions {

    private ArgNameCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        String directiveName = Nodes.text(directive.nameNode(), source);
        var dirDef = vocabulary.registry().getDirectiveDefinition(directiveName);
        if (dirDef.isEmpty()) return List.of();

        Directives.Argument enclosing = null;
        for (var arg : directive.arguments()) {
            if (arg.contains(pos)) {
                enclosing = arg;
                break;
            }
        }

        if (enclosing == null) {
            // Cursor inside the directive's parens but not on any argument:
            // top-level arg-name completion.
            return toCompletionItems(dirDef.get().getInputValueDefinitions());
        }

        // Cursor is inside an argument value. If it's inside a nested
        // object_value but outside every object_field of that object_value,
        // we're at a nested-arg-name slot; otherwise no arg-name
        // completion applies.
        String argName = Nodes.text(enclosing.key(), source);
        var argDef = LspVocabulary.findInputValue(dirDef.get().getInputValueDefinitions(), argName);
        if (argDef.isEmpty()) return List.of();
        String currentType = LspVocabulary.unwrapToInputTypeName(argDef.get().getType());
        if (currentType == null) return List.of();

        Node objectValue = innermostObjectValueAt(enclosing.value(), pos);
        if (objectValue == null) return List.of();
        if (cursorInsideAnyObjectField(objectValue, pos)) return List.of();

        // Walk down through the input-type field tree following the chain
        // of object_field ancestors that contain pos. The innermost
        // object_value we landed on belongs to whatever input type is
        // referenced by the enclosing object_field's name (or the arg
        // itself, if no enclosing object_field).
        var ancestorChain = collectEnclosingFieldChain(enclosing.value(), objectValue, source);
        for (String fieldName : ancestorChain) {
            var inputType = vocabulary.registry()
                .getTypeOrNull(currentType, InputObjectTypeDefinition.class);
            if (inputType == null) return List.of();
            var fieldDef = LspVocabulary.findInputValue(inputType.getInputValueDefinitions(), fieldName);
            if (fieldDef.isEmpty()) return List.of();
            String next = LspVocabulary.unwrapToInputTypeName(fieldDef.get().getType());
            if (next == null) return List.of();
            currentType = next;
        }

        var inputType = vocabulary.registry()
            .getTypeOrNull(currentType, InputObjectTypeDefinition.class);
        if (inputType == null) return List.of();
        return toCompletionItems(inputType.getInputValueDefinitions());
    }

    private static List<CompletionItem> toCompletionItems(List<InputValueDefinition> defs) {
        var items = new ArrayList<CompletionItem>();
        for (var def : defs) {
            var item = new CompletionItem(def.getName());
            item.setKind(CompletionItemKind.Field);
            items.add(item);
        }
        return items;
    }

    /**
     * Innermost {@code object_value} containing {@code pos}, or null when
     * the cursor sits on a non-object-value part of the arg value.
     */
    private static Node innermostObjectValueAt(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = "object_value".equals(node.getType()) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostObjectValueAt(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static boolean cursorInsideAnyObjectField(Node objectValue, Point pos) {
        for (int i = 0; i < objectValue.getChildCount(); i++) {
            Node child = objectValue.getChild(i).orElse(null);
            if (child == null || !"object_field".equals(child.getType())) continue;
            if (Nodes.contains(child, pos)) return true;
        }
        return false;
    }

    /**
     * Outermost-first list of {@code object_field} names along the path
     * from {@code argRoot} down to (but not including) the
     * {@code object_value} we landed on. For example, in
     * {@code @reference(path: [{condition: <cursor in here>}])} this
     * returns {@code ["condition"]}; the resolver applies that to walk
     * one level into {@code ReferenceElement.condition}'s input type
     * before emitting completions.
     */
    private static List<String> collectEnclosingFieldChain(Node argRoot, Node target, byte[] source) {
        var out = new ArrayList<String>();
        descend(argRoot, target, source, out);
        return out;
    }

    private static boolean descend(Node node, Node target, byte[] source, List<String> out) {
        if (node == null) return false;
        if (sameNode(node, target)) return true;
        if (!nodeContains(node, target)) return false;
        if ("object_field".equals(node.getType())) {
            Node nameNode = childOfKind(node, "name");
            Node valueNode = childOfKind(node, "value");
            if (nameNode != null && valueNode != null && nodeContains(valueNode, target)) {
                if (descend(valueNode, target, source, out)) {
                    out.add(0, Nodes.text(nameNode, source));
                    return true;
                }
            }
            return false;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (descend(node.getChild(i).orElse(null), target, source, out)) return true;
        }
        return false;
    }

    private static boolean nodeContains(Node parent, Node child) {
        return parent.getStartByte() <= child.getStartByte()
            && parent.getEndByte() >= child.getEndByte();
    }

    /**
     * Tree-sitter node identity: two {@link Node} handles obtained from
     * different tree walks may not be reference-equal even when they
     * point at the same syntactic node. Compare by byte range + grammar
     * type instead, which uniquely identifies a node within a parse.
     */
    private static boolean sameNode(Node a, Node b) {
        return a.getStartByte() == b.getStartByte()
            && a.getEndByte() == b.getEndByte()
            && a.getType().equals(b.getType());
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }
}
