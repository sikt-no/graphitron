package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Class-name completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.ClassNameBinding}. The dispatch
 * is identity-keyed: the cursor's coordinate (carried on
 * {@link CompletionContext}) is looked up in the overlay; if the result
 * is a {@code ClassNameBinding}, this provider emits the catalog's
 * external-reference set as completion items.
 *
 * <p>Coordinate-driven dispatch: every coordinate the canonical overlay
 * binds as a class-name slot fires this provider, including the flat
 * {@code @sourceRow(className:)} that the R110 rescope left silent
 * under the previous hand-coded directive registry.
 */
public final class ClassNameCompletions {

    private ClassNameCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        CompletionContext context
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ClassNameBinding)) {
            return List.of();
        }
        return data.externalReferences().stream()
            .map(ref -> toCompletionItem(ref, context))
            .toList();
    }

    private static CompletionItem toCompletionItem(
        CompletionData.ExternalReference ref, CompletionContext context
    ) {
        var item = new CompletionItem(ref.className());
        item.setKind(CompletionItemKind.Class);
        item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(), ref.className())));
        return item;
    }
}
