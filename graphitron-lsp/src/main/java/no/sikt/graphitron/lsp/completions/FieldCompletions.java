package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.facts.CatalogColumns;
import no.sikt.graphitron.lsp.facts.CatalogTable;
import no.sikt.graphitron.lsp.facts.ClassMemberSlots;
import no.sikt.graphitron.lsp.facts.FieldColumnScope;
import no.sikt.graphitron.lsp.facts.TypeBackingClass;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.model.read.StoreHandle;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
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
 * set depends on the enclosing GraphQL type's backing shape, looked up from
 * the {@link LspSchemaSnapshot.Built#typesByName()} projection:
 *
 * <ul>
 *   <li>{@link TypeBackingShape.TableBacking} or
 *       {@link TypeBackingShape.JooqRecordBacking} with a known table — the column census of
 *       {@code sql_column}, with the generated field's Javadoc joined in on the {@code .java}
 *       cadence.</li>
 *   <li>{@link TypeBackingShape.RecordBacking} or {@link TypeBackingShape.PojoBacking} — the member
 *       slots the backing class offers, the class itself read from {@link TypeBackingClass} and its
 *       slots from {@link ClassMemberSlots}. Neither the permit's identity nor the class name it
 *       carries decides anything here.</li>
 *   <li>{@link TypeBackingShape.NoBacking} or snapshot miss — empty list
 *       (matches today's "no info" behaviour).</li>
 * </ul>
 *
 * <p>Which table a type is bound to stays a classification question the snapshot answers, as does
 * the field's own classification; which class stands for a type is the store's, and so is what
 * either backing then offers ({@link CatalogColumns} for a table, {@link ClassMemberSlots} for a
 * class).
 */
public final class FieldCompletions {

    private FieldCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        LspSchemaSnapshot snapshot,
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
        return completionsFor(store, snapshot, context, typeName.get(), fieldName);
    }

    private static List<CompletionItem> completionsFor(
        StoreHandle store, LspSchemaSnapshot snapshot,
        CompletionContext context, String typeName, String fieldName
    ) {
        // At the payload data field site, prepend $source as a top-level completion.
        // The snapshot owns the (typeName, fieldName) -> SiteContext classification through
        // siteContext(); we route the predicate through sourceSigilDefinedAt rather than reading
        // the underlying projection ourselves. Snapshot-uncertainty rule: when the parent type
        // has no entry in the carrier projection, siteContext returns Other and the sigil is
        // not suggested.
        boolean isPayloadDataField = fieldName != null
            && snapshot instanceof LspSchemaSnapshot.Built sigilSnapshot
            && no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilDefinedAt(
                sigilSnapshot.siteContext(typeName, fieldName));
        var sigilItems = isPayloadDataField ? List.of(sourceSigilItem(context)) : List.<CompletionItem>of();
        // Prefer the site's own resolved scope over the enclosing type's backing: a @reference
        // path's terminal table, or the table the named type is itself bound to, is where the
        // column named here lives. A Silent scope offers nothing rather than falling back on the
        // parent's table, which would point the author at the wrong end of a join; no row at all
        // means the parent's own scope answers, which is the dispatch below.
        if (fieldName != null) {
            var scope = FieldColumnScope.of(store, typeName, fieldName);
            if (scope.isPresent()) {
                switch (scope.get()) {
                    case FieldColumnScope.Scope.Resolved(var table) -> {
                        return mergeWithSigil(sigilItems, tableColumnItems(store, table, context));
                    }
                    case FieldColumnScope.Scope.Silent ignored -> {
                        return mergeWithSigil(sigilItems, List.of());
                    }
                }
            }
        }
        // The parent's own scope. Which table the parent is bound to is still the projection's to
        // answer; which class stands for it is the store's now, as is what either backing then
        // offers, whether that is a table's columns or a class's member slots.
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) {
            return sigilItems;
        }
        var backing = built.typesByName().get(typeName);
        if (backing == null) return sigilItems;
        var rest = switch (backing) {
            case TypeBackingShape.RecordBacking ignored -> memberSlotItems(store, typeName, context);
            case TypeBackingShape.PojoBacking ignored -> memberSlotItems(store, typeName, context);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableColumnItems(store, j.tableName(), context);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> List.<CompletionItem>of();
            case TypeBackingShape.TableBacking t -> tableColumnItems(store, t.tableName(), context);
            case TypeBackingShape.NoBacking ignored -> List.<CompletionItem>of();
        };
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
            no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL,
            CompletionItemKind.Keyword, context.replaceRange(),
            "Root-value sigil — bind to the upstream Java value as a whole");
    }

    /**
     * The columns of every table this graph's census holds under {@code tableName}, in table-
     * definition order. The read itself is {@link CatalogColumns}, shared with hover's column arm.
     *
     * <p>A name two schemas both declare contributes both column lists. The projection answered
     * with whichever table its list happened to hold first, which was the generated {@code Tables}
     * class's field order rather than a resolution rule; offering both is what the census says.
     */
    private static List<CompletionItem> tableColumnItems(
        StoreHandle store, String tableName, CompletionContext context
    ) {
        return toItems(CatalogColumns.of(store, tableName), context);
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
     * The member names the class backing {@code typeName} offers, by name. Both the class and what
     * it offers are the store's now: the binding through {@link TypeBackingClass} and the members
     * through {@link ClassMemberSlots}, whose own rule decides between components and accessors. So
     * this arm depends neither on which of the two class permits routed it here nor on the class
     * name that permit carries, and a type the store cannot name one class for offers nothing.
     */
    private static List<CompletionItem> memberSlotItems(
        StoreHandle store, String typeName, CompletionContext context
    ) {
        return TypeBackingClass.of(store, typeName)
            .map(className -> ClassMemberSlots.of(store, className).stream()
                .map(slot -> toMemberSlotItem(slot, context))
                .toList())
            .orElse(List.of());
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
