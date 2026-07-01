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
 * Method-name completions specialised for {@code @externalField}: where the
 * generic {@link MethodCompletions} offers every public method of the
 * referenced class, this provider narrows the list to the methods that match
 * the {@code @externalField} contract, a static lifter returning a jOOQ
 * {@code Field<X>} and taking the parent table as its single parameter.
 *
 * <p>Discrimination is by enclosing directive name (carried on
 * {@link CompletionContext}), mirroring the {@code @record} carve-out in
 * {@link ClassNameCompletions}: the {@code ExternalCodeReference.method}
 * coordinate is shared across {@code @service} / {@code @condition} /
 * {@code @enum}, so this provider fires only under {@code @externalField} and
 * the dispatch site runs it ahead of {@link MethodCompletions} so the narrowed
 * list wins. When the class exposes no matching method the provider returns
 * empty and dispatch falls through to the generic method list.
 *
 * <p>The match is the catalog-derivable signature shape: a single parameter
 * and a {@code Field} return type (the erased display name of
 * {@code org.jooq.Field<X>}). Confirming the single parameter is specifically a
 * jOOQ {@code Table} would require the classifier-driven
 * {@code Parameter.source = ParamSource.Table} projection, which is generator-
 * side work the LSP catalog does not carry today (out of scope for R90); the
 * shape filter is the in-scope approximation.
 */
public final class ExternalFieldCompletions {

    /** Directive whose method slot this provider specialises. */
    private static final String EXTERNAL_FIELD_DIRECTIVE = "externalField";

    /** Erased display name of a jOOQ {@code Field<X>} return type. */
    private static final String FIELD_RETURN_TYPE = "Field";

    private ExternalFieldCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        CompletionContext context,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        if (!EXTERNAL_FIELD_DIRECTIVE.equals(context.directiveName())) {
            return List.of();
        }
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
            .filter(ExternalFieldCompletions::isExternalFieldMethod)
            .map(m -> toCompletionItem(m, context))
            .toList();
    }

    private static boolean isExternalFieldMethod(CompletionData.Method method) {
        return method.parameters().size() == 1
            && FIELD_RETURN_TYPE.equals(method.returnType());
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
