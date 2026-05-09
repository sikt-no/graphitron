package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import io.github.treesitter.jtreesitter.Point;

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
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var coord = vocabulary.coordinateAt(directive, pos, source);
        if (coord.isEmpty()) return List.of();
        var behavior = vocabulary.behaviorAt(coord.get());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogTableBinding)) {
            return List.of();
        }
        return data.tables().stream()
            .map(TableCompletions::toCompletionItem)
            .toList();
    }

    private static CompletionItem toCompletionItem(CompletionData.Table table) {
        var item = new CompletionItem(table.name());
        item.setKind(CompletionItemKind.Class);
        if (!table.description().isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, table.description())
            ));
        }
        return item;
    }
}
