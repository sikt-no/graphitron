package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;

/**
 * Catalog column-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.CatalogColumnBinding}. The candidate set is the
 * columns of the table declared on the enclosing GraphQL type's
 * {@code @table} directive; if the enclosing type is not table-backed,
 * no completions fire.
 */
public final class FieldCompletions {

    private FieldCompletions() {}

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
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogColumnBinding)) {
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
