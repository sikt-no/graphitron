package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import io.github.treesitter.jtreesitter.Point;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

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
        CompletionContext context,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.MethodNameBinding mnb)) {
            return List.of();
        }
        Optional<String> classFqn = vocabulary.siblingStringAt(directive, pos, mnb.classNameCoord(), source);
        if (classFqn.isEmpty()) return List.of();
        Optional<CompletionData.ExternalReference> ref = data.externalReferences().stream()
            .filter(r -> r.className().equals(classFqn.get()))
            .findFirst();
        if (ref.isEmpty()) return List.of();
        return ref.get().methods().stream()
            .map(m -> toCompletionItem(m, context))
            .toList();
    }

    private static CompletionItem toCompletionItem(CompletionData.Method method, CompletionContext context) {
        var item = new CompletionItem(method.name());
        item.setKind(CompletionItemKind.Method);
        item.setDetail(formatSignature(method));
        item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(), method.name())));
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
