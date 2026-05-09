package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.SchemaCoordinate;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.List;
import java.util.Optional;

/**
 * Method-name completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.MethodNameBinding}. The behavior
 * arm carries the sibling class-name coordinate; this provider reads the
 * value at that coordinate (the FQN the user has filled in for
 * {@code className}) and offers the public methods of that class.
 *
 * <p>If the sibling {@code className} value is missing, empty, or names a
 * class the catalog scan does not know about, the provider returns no
 * completions. The class-name itself is the user's previous edit; this
 * provider only acts once that has resolved.
 */
public final class MethodCompletions {

    private MethodCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var coord = vocabulary.coordinateAt(directive, pos, source);
        if (coord.isEmpty()) return List.of();
        var behavior = vocabulary.behaviorAt(coord.get());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.MethodNameBinding mnb)) {
            return List.of();
        }
        Optional<String> classFqn = readSiblingValue(directive, pos, mnb.classNameCoord(), source);
        if (classFqn.isEmpty()) return List.of();
        Optional<CompletionData.ExternalReference> ref = data.externalReferences().stream()
            .filter(r -> r.className().equals(classFqn.get()))
            .findFirst();
        if (ref.isEmpty()) return List.of();
        return ref.get().methods().stream()
            .map(MethodCompletions::toCompletionItem)
            .toList();
    }

    /**
     * Reads the string value at {@code siblingCoord}, scoped to the same
     * directive the cursor lives in. For
     * {@link SchemaCoordinate.InputField} the sibling lives in the same
     * nested object literal as the cursor; for
     * {@link SchemaCoordinate.DirectiveArg} the sibling is a peer
     * argument on the directive itself.
     */
    private static Optional<String> readSiblingValue(
        Directives.Directive directive,
        Point pos,
        SchemaCoordinate siblingCoord,
        byte[] source
    ) {
        return switch (siblingCoord) {
            case SchemaCoordinate.DirectiveArg da -> readDirectiveArgString(directive, da.arg(), source);
            case SchemaCoordinate.InputField f -> readSiblingObjectField(directive, pos, f.field(), source);
            case SchemaCoordinate.Directive ignored -> Optional.empty();
            case SchemaCoordinate.InputType ignored -> Optional.empty();
        };
    }

    private static Optional<String> readDirectiveArgString(
        Directives.Directive directive, String argName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (argName.equals(Nodes.text(arg.key(), source))) {
                String raw = Nodes.unquote(Nodes.text(arg.value(), source));
                return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
            }
        }
        return Optional.empty();
    }

    /**
     * Walks the directive's argument trees, descends through the cursor's
     * enclosing {@code object_value}, and returns the string value of the
     * sibling {@code object_field} named {@code fieldName}. Equivalent to
     * the legacy {@code readNestedStringField}, scoped to the cursor's
     * own object.
     */
    private static Optional<String> readSiblingObjectField(
        Directives.Directive directive, Point pos, String fieldName, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            Node objectValue = enclosingObjectValue(arg.value(), pos);
            if (objectValue == null) return Optional.empty();
            for (int i = 0; i < objectValue.getChildCount(); i++) {
                Node child = objectValue.getChild(i).orElse(null);
                if (child == null || !"object_field".equals(child.getType())) continue;
                Node nameNode = childOfKind(child, "name");
                Node valueNode = childOfKind(child, "value");
                if (nameNode == null || valueNode == null) continue;
                if (fieldName.equals(Nodes.text(nameNode, source))) {
                    String raw = Nodes.unquote(Nodes.text(valueNode, source));
                    return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
                }
            }
        }
        return Optional.empty();
    }

    private static Node enclosingObjectValue(Node node, Point pos) {
        if (node == null || !Nodes.contains(node, pos)) return null;
        Node best = "object_value".equals(node.getType()) ? node : null;
        for (int i = 0; i < node.getChildCount(); i++) {
            Node descendant = enclosingObjectValue(node.getChild(i).orElse(null), pos);
            if (descendant != null) best = descendant;
        }
        return best;
    }

    private static Node childOfKind(Node parent, String kind) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            Node child = parent.getChild(i).orElse(null);
            if (child != null && kind.equals(child.getType())) return child;
        }
        return null;
    }

    private static CompletionItem toCompletionItem(CompletionData.Method method) {
        var item = new CompletionItem(method.name());
        item.setKind(CompletionItemKind.Method);
        item.setDetail(formatSignature(method));
        return item;
    }

    private static String formatSignature(CompletionData.Method method) {
        var sb = new StringBuilder();
        sb.append(method.returnType()).append(' ').append(method.name()).append('(');
        for (int i = 0; i < method.parameters().size(); i++) {
            if (i > 0) sb.append(", ");
            var p = method.parameters().get(i);
            sb.append(p.type()).append(' ')
                .append(p.name() != null ? p.name() : "arg" + i);
        }
        sb.append(')');
        return sb.toString();
    }
}
