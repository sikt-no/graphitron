package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.Positions;
import no.sikt.graphitron.model.read.StoreHandle;
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
import static no.sikt.graphitron.model.Tables.GRAPHQL_DIRECTIVE_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;

/**
 * Argument-name completion off the SDL census. Two cursor cases:
 *
 * <ul>
 *   <li><b>Top-level.</b> Cursor inside a directive's argument list but
 *       outside any specific argument value (between args, on whitespace
 *       inside the parens), or on the key side of an argument already
 *       written. Completes the directive's formal argument names, one row
 *       of {@code graphql_directive_argument} each.</li>
 *   <li><b>Nested.</b> Cursor inside a nested {@code object_value} (the
 *       value side of an input-type-typed directive arg) but outside any
 *       specific {@code object_field}. Completes the input type's field
 *       names, descending the {@code graphql_field} tree from the
 *       argument's named type to resolve the type at the current nesting
 *       depth.</li>
 * </ul>
 *
 * <p>Either case requires the directive to be one this graph's capture read;
 * a name with no rows produces no completions (the unknown-directive
 * diagnostic surfaces the typo elsewhere).
 *
 * <p>One relation for graphitron's bundled directives and an author's own alike, because capture
 * parses the bundled definitions like any other schema file. That collapses the incumbent's
 * bundled-versus-user split, and with it an asymmetry the split had carried: only the bundled arm
 * descended into nested object literals, since the projection of user directives held argument names
 * and no input-object shapes. Nesting is the same descent for both here, which is not a feature added
 * so much as a distinction the census cannot express.
 */
public final class ArgNameCompletions {

    /** The {@code graphql_type.kind} value a nested arg-name slot descends through. */
    private static final String INPUT_OBJECT_KIND = "INPUT_OBJECT";

    private ArgNameCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        Directives.Directive directive,
        Point pos,
        Position lspPos,
        byte[] source
    ) {
        String directiveName = Nodes.text(directive.nameNode(), source);

        Directives.Argument enclosing = null;
        for (var arg : directive.arguments()) {
            if (arg.contains(pos)) {
                enclosing = arg;
                break;
            }
        }

        Range range = replaceRangeFor(directive, pos, lspPos, source);

        if (enclosing == null) {
            // Cursor inside the directive's parens but not on any argument: top-level arg names.
            return items(directiveArgumentNames(store, directiveName), range);
        }
        // Cursor on the arg-key side of an existing arg ("partial arg-name identifier"): still
        // top-level territory, the author is editing the key rather than the value.
        if (Nodes.contains(enclosing.key(), pos) && !Nodes.contains(enclosing.value(), pos)) {
            return items(directiveArgumentNames(store, directiveName), range);
        }
        return nestedGenerate(store, directiveName, enclosing, pos, source, range);
    }

    /**
     * Cursor inside an argument value. Completes only at a nested-arg-name slot: inside an
     * {@code object_value} and outside every {@code object_field} of it. The chain of enclosing
     * {@code object_field} names is what says how deep the cursor is, and each step is a lookup of
     * that field's named type, so a nesting the store cannot follow (a step that is not an input
     * object's field) answers with nothing rather than with the wrong level's names.
     */
    private static List<CompletionItem> nestedGenerate(
        StoreHandle store, String directiveName, Directives.Argument enclosing,
        Point pos, byte[] source, Range range
    ) {
        Node objectValue = innermostObjectValueAt(enclosing.value(), pos);
        if (objectValue == null) return List.of();
        if (cursorInsideAnyObjectField(objectValue, pos)) return List.of();

        String argName = Nodes.text(enclosing.key(), source);
        String currentType = argumentNamedType(store, directiveName, argName);
        for (String fieldName : collectEnclosingFieldChain(enclosing.value(), objectValue, source)) {
            if (currentType == null) return List.of();
            currentType = inputFieldNamedType(store, currentType, fieldName);
        }
        if (currentType == null) return List.of();
        return items(inputFieldNames(store, currentType), range);
    }

    /** The directive definition's formal arguments, in declaration order. */
    private static List<String> directiveArgumentNames(StoreHandle store, String directiveName) {
        return store.dsl()
            .select(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME)
            .from(GRAPHQL_DIRECTIVE_ARGUMENT)
            .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.eq(directiveName))
            .orderBy(GRAPHQL_DIRECTIVE_ARGUMENT.ORDINAL)
            .fetch(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME);
    }

    /** The type an argument's expression bottoms out in, whatever wrapping it carries. */
    private static String argumentNamedType(StoreHandle store, String directiveName, String argName) {
        return store.dsl()
            .select(GRAPHQL_DIRECTIVE_ARGUMENT.NAMED_TYPE)
            .from(GRAPHQL_DIRECTIVE_ARGUMENT)
            .where(GRAPHQL_DIRECTIVE_ARGUMENT.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_DIRECTIVE_ARGUMENT.DIRECTIVE_NAME.eq(directiveName))
            .and(GRAPHQL_DIRECTIVE_ARGUMENT.ARGUMENT_NAME.eq(argName))
            .fetchOne(GRAPHQL_DIRECTIVE_ARGUMENT.NAMED_TYPE);
    }

    /**
     * The named type of one field of an input object, or null when the type is not an input object or
     * declares no such field. The kind check is the guard the incumbent got from graphql-java refusing
     * to hand back an {@code InputObjectTypeDefinition} for anything else: {@code graphql_field} holds
     * output fields under the same shape, and only the join to {@code graphql_type} tells them apart.
     */
    private static String inputFieldNamedType(StoreHandle store, String typeName, String fieldName) {
        return store.dsl()
            .select(GRAPHQL_FIELD.NAMED_TYPE)
            .from(GRAPHQL_FIELD)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_FIELD.TYPE_NAME.eq(typeName))
            .and(GRAPHQL_FIELD.FIELD_NAME.eq(fieldName))
            .and(GRAPHQL_TYPE.KIND.eq(INPUT_OBJECT_KIND))
            .fetchOne(GRAPHQL_FIELD.NAMED_TYPE);
    }

    /** An input object's field names, in the effective type's declaration order. */
    private static List<String> inputFieldNames(StoreHandle store, String typeName) {
        return store.dsl()
            .select(GRAPHQL_FIELD.FIELD_NAME)
            .from(GRAPHQL_FIELD)
            .join(GRAPHQL_TYPE).on(GRAPHQL_TYPE.GRAPH_NAME.eq(GRAPHQL_FIELD.GRAPH_NAME)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(GRAPHQL_FIELD.TYPE_NAME)))
            .where(GRAPHQL_FIELD.GRAPH_NAME.eq(store.graphName()))
            .and(GRAPHQL_FIELD.TYPE_NAME.eq(typeName))
            .and(GRAPHQL_TYPE.KIND.eq(INPUT_OBJECT_KIND))
            .orderBy(GRAPHQL_FIELD.ORDINAL)
            .fetch(GRAPHQL_FIELD.FIELD_NAME);
    }

    private static List<CompletionItem> items(List<String> names, Range range) {
        var items = new ArrayList<CompletionItem>(names.size());
        for (String name : names) {
            items.add(toCompletionItem(name, range));
        }
        return items;
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
