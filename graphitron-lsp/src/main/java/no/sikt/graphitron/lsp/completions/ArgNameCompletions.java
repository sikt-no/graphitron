package no.sikt.graphitron.lsp.completions;

import graphql.language.DirectiveDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.InputValueDefinition;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.lsp.state.DirectiveResolution;
import no.sikt.graphitron.rewrite.catalog.DirectiveShape;
import no.sikt.graphitron.rewrite.catalog.InputValueShape;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.NAME;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_FIELD;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.OBJECT_VALUE;
import static no.sikt.graphitron.lsp.parsing.GraphqlNodeKind.VALUE;

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
        LspSchemaSnapshot snapshot,
        Directives.Directive directive,
        Point pos,
        Position lspPos,
        byte[] source
    ) {
        String directiveName = Nodes.text(directive.nameNode(), source);
        var resolution = DirectiveResolution.resolve(vocabulary, snapshot, directiveName);

        Directives.Argument enclosing = null;
        for (var arg : directive.arguments()) {
            if (arg.contains(pos)) {
                enclosing = arg;
                break;
            }
        }

        Range range = replaceRangeFor(directive, pos, lspPos, source);

        return switch (resolution) {
            case DirectiveResolution.Bundled bundled ->
                bundledGenerate(bundled.def(), vocabulary, enclosing, pos, source, range);
            case DirectiveResolution.User user ->
                userGenerate(user.shape(), enclosing, range);
            case DirectiveResolution.Unknown ignored -> List.of();
        };
    }

    /**
     * Range to replace when the user accepts a suggestion. If the cursor
     * sits on a {@code name} node (a partial arg-name identifier inside
     * the directive's argument tree), the range is that node's full span;
     * otherwise (whitespace inside the directive's parens, or inside an
     * {@code object_value} between {@code object_field}s) the range is
     * zero-width at the cursor. The discrimination mirrors the spec's
     * "cursor-on-{@code name} vs. not" rule for the {@code ArgNameCompletions}
     * provider.
     */
    private static Range replaceRangeFor(
        Directives.Directive directive, Point pos, Position lspPos, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            Node name = innermostNameAt(arg.full(), pos);
            if (name != null) {
                return new Range(
                    Positions.toLspPosition(source, name.getStartByte()),
                    Positions.toLspPosition(source, name.getEndByte()));
            }
        }
        return new Range(lspPos, lspPos);
    }

    private static Node innermostNameAt(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = NAME.matches(node) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostNameAt(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    /**
     * Top-level arg-name completion for a user-declared directive. Phase 2
     * does not descend into nested object literals (the snapshot does not
     * carry input-object shapes yet); when the cursor sits inside an
     * argument value, return empty. Completion is freshness-agnostic:
     * stale suggestions beat silence for an editor surface.
     *
     * <p>Asymmetric with {@link #bundledGenerate}: the bundled arm fires
     * on the arg-key side of an already-filled arg (partial-identifier
     * editing), the user arm does not. The asymmetry is incidental to
     * the wire-format scope and would warrant its own roadmap item if
     * a user-directive partial-identifier completion gap surfaces.
     */
    private static List<CompletionItem> userGenerate(
        DirectiveShape shape, Directives.Argument enclosing, Range range
    ) {
        if (enclosing != null) return List.of();
        return userToCompletionItems(shape.args(), range);
    }

    private static List<CompletionItem> userToCompletionItems(List<InputValueShape> args, Range range) {
        var items = new ArrayList<CompletionItem>();
        for (var arg : args) {
            items.add(toCompletionItem(arg.name(), range));
        }
        return items;
    }

    private static List<CompletionItem> bundledGenerate(
        DirectiveDefinition dirDef,
        LspVocabulary vocabulary,
        Directives.Argument enclosing,
        Point pos,
        byte[] source,
        Range range
    ) {
        if (enclosing == null) {
            // Cursor inside the directive's parens but not on any argument:
            // top-level arg-name completion.
            return toCompletionItems(dirDef.getInputValueDefinitions(), range);
        }

        // Cursor on the arg-key side of an existing arg ("partial arg-name
        // identifier"): still top-level arg-name territory; the user is
        // editing the key, not the value.
        if (Nodes.contains(enclosing.key(), pos) && !Nodes.contains(enclosing.value(), pos)) {
            return toCompletionItems(dirDef.getInputValueDefinitions(), range);
        }

        // Cursor is inside an argument value. If it's inside a nested
        // object_value but outside every object_field of that object_value,
        // we're at a nested-arg-name slot; otherwise no arg-name
        // completion applies.
        String argName = Nodes.text(enclosing.key(), source);
        var argDef = LspVocabulary.findInputValue(dirDef.getInputValueDefinitions(), argName);
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
        return toCompletionItems(inputType.getInputValueDefinitions(), range);
    }

    private static List<CompletionItem> toCompletionItems(List<InputValueDefinition> defs, Range range) {
        var items = new ArrayList<CompletionItem>();
        for (var def : defs) {
            items.add(toCompletionItem(def.getName(), range));
        }
        return items;
    }

    private static CompletionItem toCompletionItem(String label, Range range) {
        return CompletionItems.replacing(label, CompletionItemKind.Field, range);
    }

    /**
     * Innermost {@code object_value} containing {@code pos}, or null when
     * the cursor sits on a non-object-value part of the arg value.
     */
    private static Node innermostObjectValueAt(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = OBJECT_VALUE.matches(node) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = innermostObjectValueAt(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static boolean cursorInsideAnyObjectField(Node objectValue, Point pos) {
        for (int i = 0; i < objectValue.getChildCount(); i++) {
            Node child = objectValue.getChild(i).orElse(null);
            if (child == null || !OBJECT_FIELD.matches(child)) continue;
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
        if (Nodes.sameNode(node, target)) return true;
        if (!Nodes.nodeContains(node, target)) return false;
        if (OBJECT_FIELD.matches(node)) {
            Node nameNode = Nodes.childOfKind(node, NAME);
            Node valueNode = Nodes.childOfKind(node, VALUE);
            if (nameNode != null && valueNode != null && Nodes.nodeContains(valueNode, target)) {
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
}
