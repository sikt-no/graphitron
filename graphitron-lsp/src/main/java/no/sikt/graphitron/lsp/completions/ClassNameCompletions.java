package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DirectivePolicy;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.jooq.Field;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.min;
import static org.jooq.impl.DSL.when;

/**
 * Class-name completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.ClassNameBinding}. The dispatch
 * is identity-keyed: the cursor's coordinate (carried on
 * {@link CompletionContext}) is looked up in the overlay; if the result
 * is a {@code ClassNameBinding}, this provider offers the graph's class census.
 *
 * <p>Coordinate-driven dispatch: every coordinate the canonical overlay
 * binds as a class-name slot fires this provider, including the flat
 * {@code @sourceRow(className:)} that was left silent
 * under the previous hand-coded directive registry.
 *
 * <p>Carve-out: {@code @record} is deprecated and ignored, so its
 * {@code className} slot binds no Java class. Its
 * {@code ExternalCodeReference.className} coordinate is identical to
 * {@code @enum}'s, so the carve-out cannot key on the coordinate; it reads the
 * enclosing directive name carried by {@link CompletionContext} through
 * {@link DirectivePolicy#bindsLiveClass}.
 */
public final class ClassNameCompletions {

    /** The {@code store_source.source_kind} value naming a classpath jar. */
    private static final String JAR = "JAR";

    private ClassNameCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
        CompletionContext context
    ) {
        if (!DirectivePolicy.bindsLiveClass(context.directiveName())) {
            return List.of();
        }
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ClassNameBinding)) {
            return List.of();
        }
        // Reactor-resident classes first, jar-resident ones after, then by name. Ordering, not
        // filtering: every class the graph's walk met is legitimately referenceable, and filtering
        // by residence would restore the bug the census widening fixed. Provenance is a join here
        // rather than a boolean on the row, which is what lets the same census also answer "from
        // which jar" without the projection's one-bit flattening.
        Field<Integer> rank = min(when(STORE_SOURCE.SOURCE_KIND.eq(JAR), inline(1)).otherwise(inline(0)));
        var rows = store.dsl()
            .select(JVM_CLASS.CLASS_NAME, rank)
            .from(JVM_CLASS)
            .join(STORE_SOURCE).on(STORE_SOURCE.SOURCE_NAME.eq(JVM_CLASS.SOURCE_NAME))
            .where(store.reads(JVM_CLASS.SOURCE_NAME))
            // Grouped by name, not listed per source: one FQN reachable from both the reactor and a
            // jar is one candidate, and the reactor copy is the one that would load, so the lower
            // rank wins. The projection could only offer it twice.
            .groupBy(JVM_CLASS.CLASS_NAME)
            .orderBy(rank, JVM_CLASS.CLASS_NAME)
            .fetch();
        var items = new ArrayList<CompletionItem>(rows.size());
        for (var row : rows) {
            String className = row.value1();
            var item = CompletionItems.replacing(className, CompletionItemKind.Class, context.replaceRange());
            // sortText carries the same rank to clients that re-sort, which most do.
            item.setSortText(row.value2() + className);
            items.add(item);
        }
        return items;
    }
}
