package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;
import java.util.Optional;

/**
 * Method-name completions for {@code @service(method:)} and
 * {@code @condition(method:)}. The candidate set is the public methods of
 * whichever class is named in the same directive's {@code class:}
 * argument. If {@code class:} is missing or names a class the scan does
 * not know about, the provider returns no completions: there's no
 * sensible cross-class union to fall back to.
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
        byte[] source
    ) {
        var argument = directive.arguments().stream()
            .filter(a -> a.contains(pos))
            .findFirst();
        if (argument.isEmpty()) return List.of();
        var arg = argument.get();
        if (!"method".equals(Nodes.text(arg.key(), source))) return List.of();
        if (!Nodes.contains(arg.value(), pos)) return List.of();

        Optional<String> classFqn = readSiblingClassFqn(directive, source);
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
     * Reads {@code class: "..."} off the same directive. Returns empty when
     * the class arg is absent or its value is empty / non-string.
     */
    private static Optional<String> readSiblingClassFqn(Directives.Directive directive, byte[] source) {
        for (var arg : directive.arguments()) {
            if (!"class".equals(Nodes.text(arg.key(), source))) continue;
            String raw = Nodes.unquote(Nodes.text(arg.value(), source));
            return raw.isEmpty() ? Optional.empty() : Optional.of(raw);
        }
        return Optional.empty();
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
            sb.append(p.type()).append(' ').append(p.name());
        }
        sb.append(')');
        return sb.toString();
    }
}
