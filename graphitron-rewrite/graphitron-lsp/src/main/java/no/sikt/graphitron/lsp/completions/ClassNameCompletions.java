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
 * Class-name completions for the directives that take an
 * {@code ExternalCodeReference} input. The schema directive surface is
 * uniform: each of {@code @service}, {@code @condition}, and
 * {@code @record} carries a single nested object under an outer arg
 * whose name matches the directive, with {@code className} / {@code method}
 * / {@code argMapping} fields on the nested object:
 *
 * <ul>
 *   <li>{@code @service(service: {className: "...", method: "...", argMapping: "..."})}</li>
 *   <li>{@code @condition(condition: {className: "...", method: "...", argMapping: "..."})}</li>
 *   <li>{@code @record(record: {className: "..."})}</li>
 * </ul>
 *
 * <p>This provider lights up when the cursor is inside the nested
 * {@code className} value. Method completion in the sibling
 * {@code method} value is in {@link MethodCompletions}.
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
        String outerArg = outerArgOf(directiveName);
        if (outerArg == null) return List.of();
        var nestedOpt = NestedArgs.findContaining(directive, pos, source);
        if (nestedOpt.isEmpty()) return List.of();
        var nested = nestedOpt.get();
        if (!outerArg.equals(nested.outerArgumentName())) return List.of();
        if (!"className".equals(nested.nestedFieldNameText())) return List.of();
        if (!Nodes.contains(nested.nestedValue(), pos)) return List.of();
        return data.externalReferences().stream()
            .map(ClassNameCompletions::toCompletionItem)
            .toList();
    }

    /**
     * Maps a directive name to the outer-arg key under which its
     * {@link no.sikt.graphitron.rewrite.catalog.CompletionData.ExternalReference}
     * input lives. Mirrors the directive definitions in
     * {@code directives.graphqls}.
     */
    static String outerArgOf(String directiveName) {
        return switch (directiveName) {
            case "service" -> "service";
            case "condition" -> "condition";
            case "record" -> "record";
            default -> null;
        };
    }

    private static CompletionItem toCompletionItem(CompletionData.ExternalReference ref) {
        var item = new CompletionItem(ref.className());
        item.setKind(CompletionItemKind.Class);
        return item;
    }
}
