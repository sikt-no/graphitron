package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.facts.CatalogColumns;
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
 *   <li>{@link TypeBackingShape.RecordBacking} — record-component names off
 *       the backing class's {@code Record} attribute.</li>
 *   <li>{@link TypeBackingShape.PojoBacking} — bean-accessor names off the
 *       backing class's public method set.</li>
 *   <li>{@link TypeBackingShape.NoBacking} or snapshot miss — empty list
 *       (matches today's "no info" behaviour).</li>
 * </ul>
 *
 * <p>Which table a site's columns come from stays a classification question, so the enclosing
 * type's backing and the field's own classification are read from the snapshot; only the column
 * census itself is a store read, and that read is {@link CatalogColumns}, shared with hover's
 * column arm.
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
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) {
            return List.of();
        }
        // At the payload data field site, prepend $source as a top-level completion.
        // The snapshot owns the (typeName, fieldName) -> SiteContext classification through
        // siteContext(); we route the predicate through sourceSigilDefinedAt rather than reading
        // the underlying projection ourselves. Snapshot-uncertainty rule: when the parent type
        // has no entry in the carrier projection, siteContext returns Other and the sigil is
        // not suggested.
        boolean isPayloadDataField = fieldName != null
            && no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilDefinedAt(
                built.siteContext(typeName, fieldName));
        var sigilItems = isPayloadDataField ? List.of(sourceSigilItem(context)) : List.<CompletionItem>of();
        // Prefer the field classification's projected terminal table over the enclosing
        // type's backing for @reference path fields and the other column-bearing permits.
        // lspColumnDispatch() collapses the permits onto three arms; Resolve and Silent
        // each return directly through mergeWithSigil, FallThrough drops through to the
        // existing backing-driven dispatch below. Snapshot-uncertainty (empty optional)
        // also falls through.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName, fieldName);
            if (classification.isPresent()) {
                switch (classification.get().lspColumnDispatch()) {
                    case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                        return mergeWithSigil(sigilItems, tableColumnItems(store, tableName, context));
                    }
                    case FieldClassification.LspColumnDispatch.Silent ignored -> {
                        return mergeWithSigil(sigilItems, List.of());
                    }
                    case FieldClassification.LspColumnDispatch.FallThrough ignored -> { /* fall through */ }
                }
            }
        }
        var backing = built.typesByName().get(typeName);
        if (backing == null) return sigilItems;
        var rest = switch (backing) {
            case TypeBackingShape.RecordBacking r -> memberSlotItems(r.components(), context);
            case TypeBackingShape.PojoBacking p -> memberSlotItems(p.accessors(), context);
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
        var columns = CatalogColumns.of(store, tableName);
        var items = new ArrayList<CompletionItem>(columns.size());
        for (var column : columns) {
            items.add(toColumnItem(column, context));
        }
        return items;
    }

    private static List<CompletionItem> memberSlotItems(
        List<TypeBackingShape.MemberSlot> slots, CompletionContext context
    ) {
        return slots.stream().map(s -> toMemberSlotItem(s, context)).toList();
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

    private static CompletionItem toMemberSlotItem(TypeBackingShape.MemberSlot slot, CompletionContext context) {
        return CompletionItems.replacing(
            slot.name(), CompletionItemKind.Field, context.replaceRange(), slot.displayType());
    }
}
