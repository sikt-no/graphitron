package no.sikt.graphitron.lsp.completions;

import io.github.treesitter.jtreesitter.Point;
import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.ScalarTypeResolver;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Completion for {@code @scalarType(scalar: "|")} on a {@code scalar X}
 * declaration. Suggests entries from
 * {@link ScalarTypeResolver#conventionTable()}, prioritising the FQN
 * whose convention-table key matches the enclosing scalar's SDL name.
 *
 * <p>The convention layer is the easy-onramp story: if the consumer has
 * {@code graphql-java-extended-scalars} on the classpath, the matching
 * convention FQN is the single right answer. We do not try to enumerate
 * static {@code GraphQLScalarType} fields off the consumer's compile
 * classpath here. {@link CompletionData.ExternalReference} carries
 * methods but not field metadata, so the catalog cannot tell us which
 * classes expose scalar constants. Consumers binding a custom scalar
 * type fall back to typing the FQN by hand; the diagnostic surface
 * (in {@code Diagnostics}) tells them whether the class exists.
 */
public final class ScalarTypeCompletions {

    private ScalarTypeCompletions() {}

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
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ScalarTypeBinding)) {
            return List.of();
        }
        String scalarName = TypeContext.enclosingTypeDefinition(directive.outer())
            .filter(n -> "scalar_type_definition".equals(n.getType()))
            .flatMap(n -> TypeContext.declaredNameOf(n, source))
            .orElse(null);

        var table = ScalarTypeResolver.conventionTable();
        Set<String> fqns = new LinkedHashSet<>();
        if (scalarName != null) {
            String preferred = table.get(scalarName);
            if (preferred != null) fqns.add(preferred);
        }
        fqns.addAll(table.values());
        var items = new ArrayList<CompletionItem>(fqns.size());
        for (String fqn : fqns) {
            var item = new CompletionItem(fqn);
            item.setKind(CompletionItemKind.Constant);
            item.setDetail("extended-scalars convention");
            items.add(item);
        }
        return items;
    }
}
