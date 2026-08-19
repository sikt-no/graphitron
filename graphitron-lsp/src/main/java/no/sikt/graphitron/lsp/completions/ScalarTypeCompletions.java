package no.sikt.graphitron.lsp.completions;

import no.sikt.graphitron.lsp.parsing.Behavior;
import no.sikt.graphitron.lsp.parsing.DeclarationKind;
import no.sikt.graphitron.lsp.parsing.Directives;
import no.sikt.graphitron.lsp.parsing.LspVocabulary;
import no.sikt.graphitron.lsp.parsing.TypeContext;
import no.sikt.graphitron.model.read.StoreHandle;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.jooq.Field;

import java.util.ArrayList;
import java.util.List;

import static no.sikt.graphitron.model.Tables.JVM_SCALAR_TYPE_FIELD;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.inline;
import static org.jooq.impl.DSL.lower;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.when;

/**
 * Completion for {@code @scalarType(scalar: "|")} on a {@code scalar X}
 * declaration. Suggests {@code className.fieldName} for each
 * {@code public static GraphQLScalarType} constant the graph's classpath walk
 * met, prioritising the constant whose field name matches the enclosing
 * scalar's SDL name.
 *
 * <p>The candidates are {@code jvm_scalar_type_field} rows: the walk enumerates
 * the {@code GraphQLScalarType} fields actually on the classpath, so it surfaces
 * the consumer's own scalar constants ({@code com.example.Scalars.MONEY}) as
 * well as any library's, with no coupling to
 * {@code graphql-java-extended-scalars}. Every suggestion is a well-formed
 * {@code class.field} reference as
 * {@link no.sikt.graphitron.model.grammar.ConstantReferenceGrammar}
 * defines the shape, so a completed value never rejects as malformed at
 * codegen. The walk sees the field type, not its runtime value; a suggested
 * constant may still fail to bind (null at codegen, erased {@code Coercing}),
 * which the authored-value diagnostics in {@code Diagnostics} report. That is
 * the same best-effort contract method completion already lives under.
 */
public final class ScalarTypeCompletions {

    private ScalarTypeCompletions() {}

    public static List<CompletionItem> generate(
        LspVocabulary vocabulary,
        StoreHandle store,
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

        // Field-name match for the enclosing `scalar X` is offered first (case-insensitive, so
        // `scalar UUID` prefers `...ExtendedScalars.UUID`); everything else follows by name. The
        // preference is a sort key rather than a partition, so the ordering is one pass over one
        // result rather than two accumulating sets. Aliased, and ordered by the alias, because a
        // DISTINCT select may only order by its own result columns and re-rendering the expression
        // in the ORDER BY makes it a second one.
        Field<Integer> rank = (scalarName == null
            ? inline(0)
            : when(lower(JVM_SCALAR_TYPE_FIELD.FIELD_NAME).eq(scalarName.toLowerCase()), inline(0))
                .otherwise(inline(1))).as("rank");
        var rows = store.dsl()
            // Distinct, because one constant reachable through two sources is one candidate: the
            // reference an author writes names the class and the field, and says nothing about which
            // classpath entry it came from.
            .selectDistinct(JVM_SCALAR_TYPE_FIELD.CLASS_NAME, JVM_SCALAR_TYPE_FIELD.FIELD_NAME, rank)
            .from(JVM_SCALAR_TYPE_FIELD)
            .where(store.reads(JVM_SCALAR_TYPE_FIELD.SOURCE_NAME))
            .orderBy(field(name("rank")), JVM_SCALAR_TYPE_FIELD.CLASS_NAME, JVM_SCALAR_TYPE_FIELD.FIELD_NAME)
            .fetch();
        var items = new ArrayList<CompletionItem>(rows.size());
        for (var row : rows) {
            items.add(CompletionItems.replacing(
                row.value1() + "." + row.value2(), CompletionItemKind.Constant,
                context.replaceRange(), "GraphQLScalarType constant"));
        }
        return items;
    }
}
