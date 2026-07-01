package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.Descriptions;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Catalog table-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.CatalogTableBinding}. Today's canonical overlay
 * declares this for {@code @table(name:)} and
 * {@code ReferenceElement.table} (the {@code table} field inside
 * {@code @reference(path: [{table:}])}). Both fire here without a
 * directive-name switch.
 */
public final class TableCompletions {

    private TableCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        SourceWalker.Index sourceIndex,
        CompletionContext context
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogTableBinding)) {
            return List.of();
        }
        return data.tables().stream()
            .map(t -> toCompletionItem(t, context, sourceIndex))
            .toList();
    }

    private static CompletionItem toCompletionItem(
        CompletionData.Table table, CompletionContext context, SourceWalker.Index sourceIndex
    ) {
        var item = CompletionItems.replacing(table.name(), CompletionItemKind.Class, context.replaceRange());
        String description = Descriptions.ofTable(table, sourceIndex);
        if (!description.isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, description)
            ));
        }
        return item;
    }
}
