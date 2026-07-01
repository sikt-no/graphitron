package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.lsp.Descriptions;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.FieldClassification;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.SourceWalker;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.List;

/**
 * Catalog-aware completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.CatalogColumnBinding}. The candidate
 * set depends on the enclosing GraphQL type's backing shape, looked up from
 * the {@link LspSchemaSnapshot.Built#typesByName()} projection:
 *
 * <ul>
 *   <li>{@link TypeBackingShape.TableBacking} or
 *       {@link TypeBackingShape.JooqRecordBacking} with a known table — jOOQ
 *       column list, routed through {@link CompletionData#getTable}.</li>
 *   <li>{@link TypeBackingShape.RecordBacking} — record-component names off
 *       the backing class's {@code Record} attribute.</li>
 *   <li>{@link TypeBackingShape.PojoBacking} — bean-accessor names off the
 *       backing class's public method set.</li>
 *   <li>{@link TypeBackingShape.NoBacking} or snapshot miss — empty list
 *       (matches today's "no info" behaviour).</li>
 * </ul>
 */
public final class FieldCompletions {

    private FieldCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        SourceWalker.Index sourceIndex,
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
        return completionsFor(data, sourceIndex, snapshot, context, typeName.get(), fieldName);
    }

    private static List<CompletionItem> completionsFor(
        CompletionData data, SourceWalker.Index sourceIndex, LspSchemaSnapshot snapshot,
        CompletionContext context, String typeName, String fieldName
    ) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) {
            return List.of();
        }
        // R159: at the payload data field site, prepend $source as a top-level completion.
        // The snapshot owns the (typeName, fieldName) -> SiteContext classification through
        // siteContext(); we route the predicate through sourceSigilDefinedAt rather than reading
        // the underlying projection ourselves. Snapshot-uncertainty rule: when the parent type
        // has no entry in the carrier projection, siteContext returns Other and the sigil is
        // not suggested.
        boolean isPayloadDataField = fieldName != null
            && no.sikt.graphitron.rewrite.FieldSourceSigil.sourceSigilDefinedAt(
                built.siteContext(typeName, fieldName));
        var sigilItems = isPayloadDataField ? List.of(sourceSigilItem(context)) : List.<CompletionItem>of();
        // R233: prefer the field classification's projected terminal table over the enclosing
        // type's backing for @reference path fields and the other column-bearing permits.
        // lspColumnDispatch() collapses the 30 permits onto three arms; Resolve and Silent
        // each return directly through mergeWithSigil, FallThrough drops through to the
        // existing backing-driven dispatch below. Snapshot-uncertainty (empty optional)
        // also falls through.
        if (fieldName != null) {
            var classification = built.fieldClassification(typeName, fieldName);
            if (classification.isPresent()) {
                switch (classification.get().lspColumnDispatch()) {
                    case FieldClassification.LspColumnDispatch.Resolve(var tableName) -> {
                        return mergeWithSigil(sigilItems, tableColumnItems(data, sourceIndex, tableName, context));
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
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableColumnItems(data, sourceIndex, j.tableName(), context);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> List.<CompletionItem>of();
            case TypeBackingShape.TableBacking t -> tableColumnItems(data, sourceIndex, t.tableName(), context);
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
        var item = new CompletionItem(no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL);
        item.setKind(CompletionItemKind.Keyword);
        item.setDetail("Root-value sigil — bind to the upstream Java value as a whole (R159)");
        item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(),
            no.sikt.graphitron.rewrite.FieldSourceSigil.UPSTREAM_ROOT_LITERAL)));
        return item;
    }

    private static List<CompletionItem> tableColumnItems(
        CompletionData data, SourceWalker.Index sourceIndex, String tableName, CompletionContext context
    ) {
        return data.getTable(tableName)
            .map(t -> t.columns().stream()
                .map(c -> toColumnItem(t, c, context, sourceIndex))
                .toList())
            .orElse(List.of());
    }

    private static List<CompletionItem> memberSlotItems(
        List<TypeBackingShape.MemberSlot> slots, CompletionContext context
    ) {
        return slots.stream().map(s -> toMemberSlotItem(s, context)).toList();
    }

    private static CompletionItem toColumnItem(
        CompletionData.Table table, CompletionData.Column column,
        CompletionContext context, SourceWalker.Index sourceIndex
    ) {
        var item = new CompletionItem(column.name());
        item.setKind(CompletionItemKind.Field);
        String description = Descriptions.ofColumn(table, column, sourceIndex);
        if (!description.isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, description)
            ));
        }
        item.setDetail(column.graphqlType() + (column.nullable() ? " (nullable)" : ""));
        item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(), column.name())));
        return item;
    }

    private static CompletionItem toMemberSlotItem(TypeBackingShape.MemberSlot slot, CompletionContext context) {
        var item = new CompletionItem(slot.name());
        item.setKind(CompletionItemKind.Field);
        item.setDetail(slot.displayType());
        item.setTextEdit(Either.forLeft(new TextEdit(context.replaceRange(), slot.name())));
        return item;
    }
}
