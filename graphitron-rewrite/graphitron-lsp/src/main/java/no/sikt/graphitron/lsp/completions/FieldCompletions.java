package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import no.sikt.graphitron.rewrite.catalog.LspSchemaSnapshot;
import no.sikt.graphitron.rewrite.catalog.TypeBackingShape;
import no.sikt.graphitron.rewrite.model.DependsOnClassifierCheck;
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
        LspSchemaSnapshot snapshot,
        CompletionContext context,
        Directives.Directive directive,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogColumnBinding)) {
            return List.of();
        }
        var typeDef = TypeContext.enclosingTypeDefinition(directive.outer());
        if (typeDef.isEmpty()) {
            return List.of();
        }
        var typeName = TypeContext.declaredNameOf(typeDef.get(), source);
        if (typeName.isEmpty()) {
            return List.of();
        }
        var fieldName = TypeContext.enclosingFieldDefinition(directive.outer())
            .flatMap(fd -> TypeContext.fieldNameOf(fd, source))
            .orElse(null);
        return completionsFor(data, snapshot, context, typeName.get(), fieldName);
    }

    @DependsOnClassifierCheck(
        key = "java-record-type-backs-record-class",
        reliesOn = "RecordBacking.components is the record's @RecordAttribute component list; "
            + "FieldCompletions emits them verbatim as @field(name:) candidates without "
            + "re-checking that the backing class is in fact a record."
    )
    private static List<CompletionItem> completionsFor(
        CompletionData data, LspSchemaSnapshot snapshot, CompletionContext context,
        String typeName, String fieldName
    ) {
        if (!(snapshot instanceof LspSchemaSnapshot.Built built)) {
            return List.of();
        }
        // R159: at the carrier-payload data field site, prepend $source as a top-level completion.
        // Snapshot-uncertainty rule: when the parent type has no entry in the carrier projection,
        // do not suggest the sigil (the snapshot's view is "shape unknown" today).
        boolean isCarrierDataField = fieldName != null
            && built.carrierDataField(typeName).filter(n -> n.equals(fieldName)).isPresent();
        var sigilItems = isCarrierDataField ? List.of(sourceSigilItem(context)) : List.<CompletionItem>of();
        var backing = built.typesByName().get(typeName);
        if (backing == null) return sigilItems;
        var rest = switch (backing) {
            case TypeBackingShape.RecordBacking r -> memberSlotItems(r.components(), context);
            case TypeBackingShape.PojoBacking p -> memberSlotItems(p.accessors(), context);
            case TypeBackingShape.JooqRecordBacking.WithTable j -> tableColumnItems(data, j.tableName(), context);
            case TypeBackingShape.JooqRecordBacking.Standalone ignored -> List.<CompletionItem>of();
            case TypeBackingShape.TableBacking t -> tableColumnItems(data, t.tableName(), context);
            case TypeBackingShape.NoBacking ignored -> List.<CompletionItem>of();
        };
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
        CompletionData data, String tableName, CompletionContext context
    ) {
        return data.getTable(tableName)
            .map(t -> t.columns().stream()
                .map(c -> toColumnItem(c, context))
                .toList())
            .orElse(List.of());
    }

    private static List<CompletionItem> memberSlotItems(
        List<TypeBackingShape.MemberSlot> slots, CompletionContext context
    ) {
        return slots.stream().map(s -> toMemberSlotItem(s, context)).toList();
    }

    private static CompletionItem toColumnItem(CompletionData.Column column, CompletionContext context) {
        var item = new CompletionItem(column.name());
        item.setKind(CompletionItemKind.Field);
        if (!column.description().isEmpty()) {
            item.setDocumentation(Either.forRight(
                new MarkupContent(MarkupKind.PLAINTEXT, column.description())
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
