package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Completion for {@code @scalarType(scalar: "|")} on a {@code scalar X}
 * declaration. Suggests {@code className.fieldName} for each
 * {@code public static GraphQLScalarType} constant found on the consumer's
 * codegen classpath, prioritising the constant whose field name matches the
 * enclosing scalar's SDL name.
 *
 * <p>The candidates come from the classpath scan carried on
 * {@link CompletionData.ExternalReference#scalarConstants()} (R464): the scan
 * enumerates the {@code GraphQLScalarType} fields actually on the classpath,
 * so it surfaces the consumer's own scalar constants
 * ({@code com.example.Scalars.MONEY}) as well as any library's, with no
 * coupling to {@code graphql-java-extended-scalars}. The scan sees the field
 * type, not its runtime value; a suggested constant may still fail to bind
 * (null at codegen, erased {@code Coercing}), which the authored-value
 * diagnostics in {@code Diagnostics} report. That is the same best-effort
 * contract method completion already lives under.
 */
public final class ScalarTypeCompletions {

    private ScalarTypeCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        CompletionData data,
        CompletionContext context,
        Directives.Directive directive,
        byte[] source
    ) {
        var behavior = vocabulary.behaviorAt(context.coordinate());
        if (behavior.isEmpty() || !(behavior.get() instanceof Behavior.ScalarTypeBinding)) {
            return List.of();
        }
        String scalarName = DeclarationKind.enclosing(directive.outer())
            .filter(n -> "scalar_type_definition".equals(n.getType()))
            .flatMap(n -> TypeContext.declaredNameOf(n, source))
            .orElse(null);

        // Field-name match for the enclosing `scalar X` is offered first (case-insensitive,
        // so `scalar UUID` prefers `...ExtendedScalars.UUID`); everything else follows.
        var preferred = new LinkedHashSet<String>();
        var rest = new LinkedHashSet<String>();
        for (CompletionData.ExternalReference ref : data.externalReferences()) {
            for (CompletionData.ScalarConstant constant : ref.scalarConstants()) {
                String fqn = ref.className() + "." + constant.fieldName();
                if (scalarName != null && constant.fieldName().equalsIgnoreCase(scalarName)) {
                    preferred.add(fqn);
                } else {
                    rest.add(fqn);
                }
            }
        }
        var fqns = new LinkedHashSet<String>(preferred);
        fqns.addAll(rest);
        var items = new ArrayList<CompletionItem>(fqns.size());
        for (String fqn : fqns) {
            items.add(CompletionItems.replacing(
                fqn, CompletionItemKind.Constant, context.replaceRange(), "GraphQLScalarType constant"));
        }
        return items;
    }
}
