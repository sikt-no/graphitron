package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.treesitter.TSPoint;

import java.util.List;

/**
 * Completions for the {@code @field(name: "...")} directive.
 *
 * <p>Resolves the table from the enclosing type's {@code @table} directive
 * and returns that table's columns as completion items. Mirrors
 * {@code completions::generate_field_completions} in the Rust LSP.
 */
public final class FieldCompletions {

    private FieldCompletions() {}

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

        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) {
            return List.of();
        }
        var tableName = TypeContext.tableNameOf(typeDef.get(), source);
        if (tableName.isEmpty()) {
            return List.of();
        }

        return data.getTable(tableName.get())
            .map(t -> t.columns().stream()
                .map(FieldCompletions::toCompletionItem)
                .toList())
            .orElse(List.of());
    }

    private static CompletionItem toCompletionItem(CompletionData.Column column) {
        var item = new CompletionItem(column.name());
        item.setKind(CompletionItemKind.Field);
        if (!column.description().isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, column.description())
            ));
        }
        item.setDetail(column.graphqlType() + (column.nullable() ? " (nullable)" : ""));
        return item;
    }
}
