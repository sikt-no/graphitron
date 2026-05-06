package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.DirectiveDefinitions;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.NestedArgs;
import no.sikt.graphitron.lsp.parsing.Nodes;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import io.github.treesitter.jtreesitter.Point;

import java.util.List;
import java.util.Optional;

/**
 * Class-name completions for any directive that takes an
 * {@code ExternalCodeReference} input. The candidate set is driven by
 * {@link DirectiveDefinitions#argsByInputType(String)}; adding a new
 * binding directive is a registry-entry change.
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
        var binding = bindingFor(directiveName);
        if (binding.isEmpty()) return List.of();
        var nestedOpt = NestedArgs.findContaining(directive, pos, source);
        if (nestedOpt.isEmpty()) return List.of();
        var nested = nestedOpt.get();
        if (!"className".equals(nested.nestedFieldNameText())) return List.of();
        if (!Nodes.contains(nested.nestedValue(), pos)) return List.of();
        if (!matchesBinding(binding.get(), nested, source)) return List.of();
        return data.externalReferences().stream()
            .map(ClassNameCompletions::toCompletionItem)
            .toList();
    }

    /**
     * Returns the (possibly nested) outer arg name for
     * {@code ExternalCodeReference} on this directive, or empty if the
     * directive does not bind {@code ExternalCodeReference}.
     *
     * <p>Migration aid for callers that only need the outer-arg name
     * (e.g. {@link MethodCompletions} reading the sibling
     * {@code className} value off the same nested object).
     */
    static Optional<String> outerArgOf(String directiveName) {
        return bindingFor(directiveName).map(DirectiveDefinitions.InputTypeBinding::argName);
    }

    private static Optional<DirectiveDefinitions.InputTypeBinding> bindingFor(String directiveName) {
        return DirectiveDefinitions.argsByInputType("ExternalCodeReference").stream()
            .filter(b -> b.directive().equals(directiveName))
            .findFirst();
    }

    /**
     * Confirms the cursor's nested-arg context lines up with the binding.
     * For flat bindings the outer argument name must equal the binding's
     * arg name. For {@code nestedPath} bindings the outer argument is
     * the structured arg (e.g. {@code path}); the binding's arg name is
     * the leaf {@code object_field} ancestor of the cursor.
     */
    private static boolean matchesBinding(
        DirectiveDefinitions.InputTypeBinding binding,
        NestedArgs.Nested nested,
        byte[] source
    ) {
        if (!binding.nestedPath()) {
            return binding.argName().equals(nested.outerArgumentName());
        }
        return NestedArgs.hasAncestorObjectField(nested, binding.argName(), source);
    }

    private static CompletionItem toCompletionItem(CompletionData.ExternalReference ref) {
        var item = new CompletionItem(ref.className());
        item.setKind(CompletionItemKind.Class);
        return item;
    }
}
