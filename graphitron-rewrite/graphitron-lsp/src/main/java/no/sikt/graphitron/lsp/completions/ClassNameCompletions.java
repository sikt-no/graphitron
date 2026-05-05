package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.NestedArgs;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;

/**
 * Class-name completions for the directives that take a Java FQN as one of
 * their arguments:
 *
 * <ul>
 *   <li>{@code @service(class: "...")} — flat string argument.</li>
 *   <li>{@code @condition(class: "...")} — flat string argument.</li>
 *   <li>{@code @record(record: {className: "..."})} — nested string field
 *       inside the {@code record:} object value.</li>
 * </ul>
 *
 * <p>Candidates come from {@link CompletionData#externalReferences()}, which
 * the {@link no.sikt.graphitron.rewrite.catalog.ClasspathScanner} populates
 * by walking the consumer's compiled {@code target/classes/} tree. Method
 * and parameter completions on those same directives land in Phase 5b.
 */
public final class ClassNameCompletions {

    private ClassNameCompletions() {}

    public static List<CompletionItem> generate(
        CompletionData data,
        Directives.Directive directive,
        Point pos,
        byte[] source,
        String directiveName
    ) {
        return switch (directiveName) {
            case "service", "condition" -> flatClassArg(data, directive, pos, source);
            case "record" -> nestedClassNameArg(data, directive, pos, source);
            default -> List.of();
        };
    }

    private static List<CompletionItem> flatClassArg(
        CompletionData data, Directives.Directive directive, Point pos, byte[] source
    ) {
        for (var arg : directive.arguments()) {
            if (!arg.contains(pos)) continue;
            if (!"class".equals(Nodes.text(arg.key(), source))) return List.of();
            if (!Nodes.contains(arg.value(), pos)) return List.of();
            return toItems(data);
        }
        return List.of();
    }

    private static List<CompletionItem> nestedClassNameArg(
        CompletionData data, Directives.Directive directive, Point pos, byte[] source
    ) {
        var nestedOpt = NestedArgs.findContaining(directive, pos, source);
        if (nestedOpt.isEmpty()) return List.of();
        var nested = nestedOpt.get();
        if (!"record".equals(nested.outerArgumentName())) return List.of();
        if (!"className".equals(nested.nestedFieldNameText())) return List.of();
        if (!Nodes.contains(nested.nestedValue(), pos)) return List.of();
        return toItems(data);
    }

    private static List<CompletionItem> toItems(CompletionData data) {
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
