package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.NestedArgs;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Node;
import io.github.treesitter.jtreesitter.Point;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.List;
import java.util.Optional;

/**
 * Method-name completions for {@code @service(service: {method: "..."})} and
 * {@code @condition(condition: {method: "..."})}. The candidate set is the
 * public methods of whichever class is named under {@code className} on the
 * same {@code ExternalCodeReference} object. If {@code className} is missing
 * or names a class the scan does not know about, the provider returns no
 * completions.
 *
 * <p>The label on each item is the method name; {@link CompletionItem#detail}
 * carries the erased return-type-and-parameters signature so editors can
 * show it inline next to the candidate.
 */
public final class MethodCompletions {

    private MethodCompletions() {}

    public static List<CompletionItem> generate(
        CompletionData data,
        Directives.Directive directive,
        Point pos,
        byte[] source,
        String directiveName
    ) {
        String outerArg = ClassNameCompletions.outerArgOf(directiveName);
        if (outerArg == null) return List.of();
        var nestedOpt = NestedArgs.findContaining(directive, pos, source);
        if (nestedOpt.isEmpty()) return List.of();
        var nested = nestedOpt.get();
        if (!outerArg.equals(nested.outerArgumentName())) return List.of();
        if (!"method".equals(nested.nestedFieldNameText())) return List.of();
        if (!Nodes.contains(nested.nestedValue(), pos)) return List.of();

        Optional<String> classFqn = readSiblingClassName(nested.outerArgument().value(), source);
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
     * Reads the {@code className} field off the same nested object the
     * cursor sits in. Walks every {@code object_field} under
     * {@code outerValue} and returns the first {@code className: "..."}
     * value when present.
     */
    static Optional<String> readSiblingClassName(Node outerValue, byte[] source) {
        return readNestedStringField(outerValue, "className", source);
    }

    static Optional<String> readNestedStringField(Node outerValue, String fieldName, byte[] source) {
        if (outerValue == null) return Optional.empty();
        if ("object_field".equals(outerValue.getType())) {
            Node nameNode = childOfKind(outerValue, "name");
            Node valueNode = childOfKind(outerValue, "value");
            if (nameNode != null && valueNode != null
                && fieldName.equals(Nodes.text(nameNode, source))) {
                String raw = Nodes.unquote(Nodes.text(valueNode, source));
                return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
            }
        }
        for (int i = 0; i < outerValue.getChildCount(); i++) {
            Node child = outerValue.getChild(i).orElse(null);
            if (child == null) continue;
            var found = readNestedStringField(child, fieldName, source);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
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
