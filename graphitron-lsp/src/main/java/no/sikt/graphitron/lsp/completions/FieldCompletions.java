package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.facts.CarrierDataField;
import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.FieldColumnTable;
import no.sikt.graphitron.lsp.facts.TypeMemberScope;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.model.grammar.FieldSourceSigilGrammar;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.List;

/**
 * Catalog-aware completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.CatalogColumnBinding}. The candidate
 * set depends on what the enclosing GraphQL type's members resolve against, which is one read of
 * {@link TypeMemberScope}:
 *
 * <ul>
 *   <li>{@link TypeMemberScope.Scope.Tables} — the column census of {@code sql_column} for every
 *       table the binding resolved to, with the generated field's Javadoc joined in on the
 *       {@code .java} cadence.</li>
 *   <li>{@link TypeMemberScope.Scope.Members} — the member slots the backing class offers, read
 *       through {@link ClassMemberSlots}.</li>
 *   <li>No scope — empty list, which a type nothing binds and no single class stands for gets.</li>
 * </ul>
 *
 * <p>Every question this provider asks is the store's. What the enclosing type resolves against is
 * one read, what either scope then offers is the next ({@link CatalogColumns} for a table,
 * {@link ClassMemberSlots} for a class), and whether the {@code $source} sigil belongs at this site
 * is {@link CarrierDataField}.
 */
public final class FieldCompletions {

    private FieldCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        CompletionContext context,
        Directives.Directive directive,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogColumnBinding)) {
            return List.of();
        }
        var typeDecl = DeclarationKind.enclosing(directive.outer());
        if (typeDecl.isEmpty()) {
            return List.of();
        }
        var typeName = TypeContext.declaredNameOf(typeDecl.get(), source);
        if (typeName.isEmpty()) {
            return List.of();
        }
        var fieldName = TypeContext.enclosingFieldOrInputValueDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, source))
            .orElse(null);
        return completionsFor(store, context, typeName.get(), fieldName);
    }

    private static List<CompletionItem> completionsFor(
        StoreHandle store, CompletionContext context, String typeName, String fieldName
    ) {
        // At the payload data field site, prepend $source as a top-level completion. Where the
        // sigil belongs is the store's answer, and a coordinate it holds no row for is one the
        // sigil does not belong at, which is the same reading as a coordinate it declines.
        boolean isPayloadDataField = fieldName != null
            && CarrierDataField.admitsSigil(store, typeName, fieldName);
        var sigilItems = isPayloadDataField ? List.of(sourceSigilItem(context)) : List.<CompletionItem>of();
        // Prefer the site's own resolved scope over the enclosing type's backing: a @reference
        // path's terminal table, or the table the named type is itself bound to, is where the
        // column named here lives. A Silent scope offers nothing rather than falling back on the
        // parent's table, which would point the author at the wrong end of a join; no row at all
        // means the parent's own scope answers, which is the dispatch below.
        if (fieldName != null) {
            var scope = FieldColumnTable.of(store, typeName, fieldName);
            if (scope.isPresent()) {
                switch (scope.get()) {
                    case FieldColumnTable.Scope.Resolved(var table) -> {
                        return mergeWithSigil(sigilItems, tableColumnItems(store, table, context));
                    }
                    case FieldColumnTable.Scope.Silent ignored -> {
                        return mergeWithSigil(sigilItems, List.of());
                    }
                }
            }
        }
        // The parent's own scope, which is one read: whether the type resolves against a table or
        // against a class, and which one, come back together, and a type the store scopes to
        // neither offers nothing.
        var rest = TypeMemberScope.of(store, typeName)
            .map(scope -> switch (scope) {
                case TypeMemberScope.Scope.Tables(var candidates) ->
                    tableColumnItems(store, candidates, context);
                case TypeMemberScope.Scope.Members(var className) ->
                    memberSlotItems(store, className, context);
            })
            .orElse(List.of());
        return mergeWithSigil(sigilItems, rest);
    }

    private static List<CompletionItem> mergeWithSigil(
        List<CompletionItem> sigilItems, List<CompletionItem> rest
    ) {
        if (sigilItems.isEmpty()) return rest;
        var combined = new java.util.ArrayList<CompletionItem>(sigilItems.size() + rest.size());
        combined.addAll(sigilItems);
        combined.addAll(rest);
        return List.copyOf(combined);
    }

    private static CompletionItem sourceSigilItem(CompletionContext context) {
        return CompletionItems.replacing(
            FieldSourceSigilGrammar.UPSTREAM_ROOT,
            CompletionItemKind.Keyword, context.replaceRange(),
            "Root-value sigil — bind to the upstream Java value as a whole");
    }

    /**
     * The columns of every table the parent's binding resolved to, in schema then definition order.
     * The read itself is {@link CatalogColumns}, shared with hover's column arm.
     *
     * <p>An ambiguous binding contributes every candidate's column list. The projection answered
     * with whichever table its list happened to hold first, which was the generated {@code Tables}
     * class's field order rather than a resolution rule; offering all of them is what the census
     * says, and each is a table the author might have meant.
     */
    private static List<CompletionItem> tableColumnItems(
        StoreHandle store, List<CatalogTable> tables, CompletionContext context
    ) {
        return toItems(CatalogColumns.of(store, tables), context);
    }

    /**
     * The columns of one resolved table. Where the spelling-keyed read above offers both schemas'
     * lists for an ambiguous name, a scope that resolved names one table and this offers only its
     * columns.
     */
    private static List<CompletionItem> tableColumnItems(
        StoreHandle store, CatalogTable table, CompletionContext context
    ) {
        return toItems(CatalogColumns.of(store, table), context);
    }

    private static List<CompletionItem> toItems(
        List<CatalogColumns.Column> columns, CompletionContext context
    ) {
        var items = new ArrayList<CompletionItem>(columns.size());
        for (var column : columns) {
            items.add(toColumnItem(column, context));
        }
        return items;
    }

    /**
     * The member names {@code className} offers, by name, read through {@link ClassMemberSlots},
     * whose own rule decides between record components and bean accessors. A class the census holds
     * nothing for offers nothing, which is a consumer who has not compiled rather than a class with
     * no members.
     */
    private static List<CompletionItem> memberSlotItems(
        StoreHandle store, String className, CompletionContext context
    ) {
        return ClassMemberSlots.of(store, className).stream()
            .map(slot -> toMemberSlotItem(slot, context))
            .toList();
    }

    /**
     * One column candidate: the generated Java field name as the label, the type jOOQ binds the
     * column to as the detail, and a description that prefers the generated field's Javadoc over
     * the database comment. That precedence is the table arm's inverted, and deliberately: a
     * column's generated Javadoc carries the qualified column name and, where the database has a
     * comment, the comment too, so it is the richer of the two rather than boilerplate.
     */
    private static CompletionItem toColumnItem(
        CatalogColumns.Column column, CompletionContext context
    ) {
        var item = CompletionItems.replacing(
            column.jooqName(), CompletionItemKind.Field, context.replaceRange(),
            column.bindingType() + (column.nullable() ? " (nullable)" : ""));
        String description = !column.javadoc().isEmpty() ? column.javadoc() : column.comment();
        if (!description.isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, description)
            ));
        }
        return item;
    }

    private static CompletionItem toMemberSlotItem(
        ClassMemberSlots.Slot slot, CompletionContext context
    ) {
        return CompletionItems.replacing(
            slot.name(), CompletionItemKind.Field, context.replaceRange(), slot.displayType());
    }
}
