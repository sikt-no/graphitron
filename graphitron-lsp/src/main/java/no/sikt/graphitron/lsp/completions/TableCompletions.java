package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.jooq.Field;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.select;

/**
 * Catalog table-name completions for any coordinate the
 * {@link LspVocabulary} overlay declares as a
 * {@link Behavior.CatalogTableBinding}. Today's canonical overlay
 * declares this for {@code @table(name:)} and
 * {@code ReferenceElement.table} (the {@code table} field inside
 * {@code @reference(path: [{table:}])}). Both fire here without a
 * directive-name switch.
 *
 * <p>The candidate set is one row per table the graph's catalog census holds, including a name two
 * schemas both declare. Offering it twice is what the relation says and what the incumbent
 * projection also did; which of the two an unqualified {@code @table(name:)} resolves to is a
 * resolution question the census deliberately leaves open, so this read does not settle it either.
 */
public final class TableCompletions {

    private TableCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        CompletionContext context
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.CatalogTableBinding)) {
            return List.of();
        }
        // The generated table class's Javadoc, on the .java cadence, joined by the FQN capture
        // recorded for exactly this purpose: jvm_class excludes the generated package, so nothing
        // else reaches these classes. A correlated scalar select rather than a left join, so a
        // duplicate declaration of one FQN cannot multiply the candidate into two popup entries;
        // file order is the tiebreak, which is arbitrary but stated and stable.
        Field<String> javadoc = field(select(JAVA_CLASS_DECLARATION.JAVADOC)
            .from(JAVA_CLASS_DECLARATION)
            .where(JAVA_CLASS_DECLARATION.CLASS_NAME.eq(SQL_TABLE.CLASS_FQN))
            .orderBy(JAVA_CLASS_DECLARATION.FILE)
            .limit(1));
        var rows = store.dsl()
            .select(SQL_TABLE.TABLE_NAME, SQL_TABLE.DESCRIPTION, javadoc)
            .from(SQL_TABLE)
            .where(store.reads(SQL_TABLE.SOURCE_NAME))
            .orderBy(SQL_TABLE.TABLE_NAME, SQL_TABLE.TABLE_SCHEMA)
            .fetch();
        var items = new ArrayList<CompletionItem>(rows.size());
        for (var row : rows) {
            var item = CompletionItems.replacing(
                row.value1(), CompletionItemKind.Class, context.replaceRange());
            // The database comment wins over the class Javadoc: for a table the generated Javadoc
            // is boilerplate naming the table back at the reader, and the comment is what somebody
            // wrote about it. The column arm inverts this, because there the Javadoc is the only
            // text a commentless column has.
            String description = description(row.value2(), row.value3());
            if (!description.isEmpty()) {
                item.setDocumentation(Either.forRight(
                    new MarkupContent(MarkupKind.PLAINTEXT, description)));
            }
            items.add(item);
        }
        return items;
    }

    private static String description(String comment, String javadoc) {
        if (comment != null && !comment.isEmpty()) {
            return comment;
        }
        return javadoc == null ? "" : javadoc;
    }
}
