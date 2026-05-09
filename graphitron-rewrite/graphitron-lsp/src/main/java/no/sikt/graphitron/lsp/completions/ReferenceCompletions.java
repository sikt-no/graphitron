package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import io.github.treesitter.jtreesitter.Point;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Foreign-key completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.CatalogFkBinding}. Today's
 * canonical overlay binds {@code ReferenceElement.key}, the {@code key}
 * field inside {@code @reference(path: [{key:}])}.
 *
 * <p>Candidates are the FK names connecting the enclosing GraphQL type's
 * table to other tables (both inbound and outbound). Path-step refinement
 * (narrowing later steps by where the previous step landed) is not yet
 * implemented; every step suggests the same set.
 */
public final class ReferenceCompletions {

    private ReferenceCompletions() {}

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
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogFkBinding)) {
            return List.of();
        }
        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) return List.of();
        var tableName = TypeContext.tableNameOf(typeDef.get(), source);
        if (tableName.isEmpty()) return List.of();
        var table = data.getTable(tableName.get());
        if (table.isEmpty()) return List.of();

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
}
