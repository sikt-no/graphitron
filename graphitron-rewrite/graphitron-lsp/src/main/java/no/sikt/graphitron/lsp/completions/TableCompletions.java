package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.catalog.CompletionData;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.treesitter.TSPoint;

import java.util.List;

/**
 * Completions for the {@code @table(name: "...")} directive.
 *
 * <p>Mirrors {@code completions::generate_table_completions} in the Rust LSP.
 * Pure function shape: takes the directive node and catalog, returns items.
 * No tree-sitter parser state held here.
 */
public final class TableCompletions {

    private TableCompletions() {}

    public static List<CompletionItem> generate(
        CompletionData data,
        Directives.Directive directive,
        TSPoint pos,
        byte[] source
    ) {
        var argument = directive.arguments().stream()
            .filter(a -> a.contains(pos))
            .findFirst();
        if (argument.isEmpty()) {
            return List.of();
        }
        var arg = argument.get();
        if (!"name".equals(Nodes.text(arg.key(), source))) {
            return List.of();
        }
        if (!Nodes.contains(arg.value(), pos)) {
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
