package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;

/**
 * Class-name completions for any coordinate the {@link LspVocabulary}
 * overlay declares as a {@link Behavior.ClassNameBinding}. The dispatch
 * is identity-keyed: the cursor's coordinate is computed once and looked
 * up in the overlay; if the result is a {@code ClassNameBinding}, this
 * provider emits the catalog's external-reference set as completion items.
 *
 * <p>The previous form lived behind a per-directive switch
 * ({@code DirectiveDefinitions.argsByInputType("ExternalCodeReference")});
 * it missed any coordinate that wasn't on that hand-coded list, which is
 * how the R110 rescope left {@code @sourceRow(className:)} silent. The
 * vocabulary-driven form picks that up the moment the canonical overlay
 * declares the binding.
 */
public final class ClassNameCompletions {

    private ClassNameCompletions() {}

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
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ClassNameBinding)) {
            return List.of();
        }
        return data.externalReferences().stream()
            .map(ClassNameCompletions::toCompletionItem)
            .toList();
    }

    private static CompletionItem toCompletionItem(CompletionData.ExternalReference ref) {
        var item = new CompletionItem(ref.className());
        item.setKind(CompletionItemKind.Class);
        return item;
    }
}
