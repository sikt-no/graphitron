package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

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

    @DependsOnClassifierCheck(
        key = "type-classification-payload-faithful",
        reliesOn = "Resolves the enclosing type's tableName off TypeClassification.Table / Node / "
            + "TableInterface / TableInput via TypeContext.tableNameOf so an extension whose "
            + "definition lives in another file still resolves to the authoritative table."
    )
    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        LspSchemaSnapshot snapshot,
        CompletionContext context,
        Directives.Directive directive,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogFkBinding)) {
            return List.of();
        }
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) return List.of();
        var tableName = TypeContext.tableNameOf(typeDecl.get(), source, snapshot);
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
            item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(), ref.keyName())));
            items.add(item);
        }
        return items;
    }
}
