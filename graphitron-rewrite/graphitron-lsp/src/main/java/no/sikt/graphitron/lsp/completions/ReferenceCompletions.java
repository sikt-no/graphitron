package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.NestedArgs;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import io.github.treesitter.jtreesitter.Point;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Completions for {@code @reference(path: [{key: "...", table: "..."}])}.
 *
 * <p>Two nested-field positions to support:
 * <ul>
 *   <li>{@code key:} suggests the foreign-key SQL constraint names that
 *       connect the enclosing type's table to other tables (the
 *       table's references list, both inbound and outbound).</li>
 *   <li>{@code table:} suggests every catalog table by name.</li>
 * </ul>
 *
 * <p>Path-step refinement (narrowing later steps by where the previous
 * step landed) is not yet implemented; every step suggests the same set
 * of FK names from the enclosing type's table.
 */
public final class ReferenceCompletions {

    private ReferenceCompletions() {}

    public static List<CompletionItem> generate(
        CompletionData data,
        Directives.Directive directive,
        Point pos,
        byte[] source
    ) {
        var nestedOpt = NestedArgs.findContaining(directive, pos, source);
        if (nestedOpt.isEmpty()) {
            return List.of();
        }
        var nested = nestedOpt.get();
        if (!"path".equals(nested.outerArgumentName())) {
            return List.of();
        }
        if (!Nodes.contains(nested.nestedValue(), pos)) {
            return List.of();
        }

        return switch (nested.nestedFieldNameText()) {
            case "key" -> keyCompletions(data, directive, source);
            case "table" -> tableCompletions(data);
            default -> List.of();
        };
    }

    private static List<CompletionItem> keyCompletions(
        CompletionData data, Directives.Directive directive, byte[] source
    ) {
        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) {
            return List.of();
        }
        var tableName = TypeContext.tableNameOf(typeDef.get(), source);
        if (tableName.isEmpty()) {
            return List.of();
        }
        var table = data.getTable(tableName.get());
        if (table.isEmpty()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        var items = new ArrayList<CompletionItem>();
        for (CompletionData.Reference ref : table.get().references()) {
            if (!seen.add(ref.keyName())) continue;
            var item = new CompletionItem(ref.keyName());
            item.setKind(CompletionItemKind.Reference);
            String detail = (ref.inverse() ? "← " : "→ ") + ref.targetTable();
            item.setDetail(detail);
            items.add(item);
        }
        return items;
    }

    private static List<CompletionItem> tableCompletions(CompletionData data) {
        return data.tables().stream()
            .map(t -> {
                var item = new CompletionItem(t.name());
                item.setKind(CompletionItemKind.Class);
                if (!t.description().isEmpty()) {
                    item.setDocumentation(Either.forRight(
                        new MarkupContent(MarkupKind.PLAINTEXT, t.description())
                    ));
                }
                return item;
            })
            .toList();
    }
}
