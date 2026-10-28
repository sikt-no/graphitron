package no.sikt.graphitron.render;

import no.sikt.graphitron.command.ReservedAliases;
import no.sikt.graphitron.command.SelectTerm;
import no.sikt.graphitron.command.SortKeyMethod;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.ParameterizedTypeName;
import no.sikt.graphitron.javapoet.WildcardTypeName;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.OrderBySpec;
import no.sikt.graphitron.rewrite.model.ParentCorrelation;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The one rendering of an {@link OrderBySpec.SortKeyEntry}: a call expression over the alias the
 * site orders on, so every order rendering (a {@code List.of(...)} part, a switch arm, a
 * {@code return}) hosts it without a statement slot. A {@code method:} key calls the consumer's
 * method; a {@code path:} key calls an emitted {@code <field>SortKey<n>} on the rendering unit,
 * whose body {@link #method} declares the hop aliases as its own locals and returns the
 * correlated single-column subselect a single-valued {@code @reference} already projects.
 */
public final class SortKeyFragments {

    private static final ClassName DSL = ClassName.get("org.jooq.impl", "DSL");
    private static final ClassName FIELD = ClassName.get("org.jooq", "Field");

    private SortKeyFragments() {}

    /**
     * The key's value for the row {@code srcAlias} names. {@code pathMethodName} gives the emitted
     * method of a {@code path:} entry's ordinal; the method is called unqualified because it lives
     * on the class that renders the order.
     */
    public static CodeBlock call(OrderBySpec.SortKeyEntry entry, String srcAlias, IntFunction<String> pathMethodName) {
        return switch (entry.key()) {
            case OrderBySpec.SortKey.Path ignored -> CodeBlock.of("$L($L)", pathMethodName.apply(entry.ordinal()), srcAlias);
            case OrderBySpec.SortKey.Method m -> CodeBlock.of("$T.$L($L)",
                ClassName.bestGuess(m.method().className()), m.method().methodName(), srcAlias);
        };
    }

    /**
     * The key's cursor column: {@link #call} aliased under {@link ReservedAliases#sortKey}, so the
     * selection merge keeps it beside a selected column of the same name and the cursor reads
     * exactly the value the order sorts on.
     */
    public static CodeBlock cursorColumn(OrderBySpec.SortKeyEntry entry, String srcAlias, IntFunction<String> pathMethodName) {
        return CodeBlock.of("$L.as($S)", call(entry, srcAlias, pathMethodName), ReservedAliases.sortKey(entry.ordinal()));
    }

    /** The name lookup {@link #call} takes, over the methods a command carries. */
    public static IntFunction<String> names(List<SortKeyMethod> methods) {
        return ordinal -> methods.stream()
            .filter(m -> m.entry().ordinal() == ordinal)
            .findFirst()
            .map(m -> m.method().methodName())
            .orElseThrow(() -> new IllegalStateException(
                "Graphitron generator bug (sort-key rendering): no emitted method was minted for the"
                + " path: sort key " + ordinal + "; the producer mints one per path entry of the order"));
    }

    /**
     * {@code private static Field<?> <name>(<Table> table)}: the emitted method behind a
     * {@code path:} key. Each hop's alias is a local of this method, declared under the SQL alias
     * {@code table.getName() + "_" + <local>} (the scheme the projection unit's subselect terms
     * use), so it always extends the outer alias the correlation reads and never meets a local of
     * the calling site or of another key. Every hop is a to-one key join, so the subselect's
     * {@code limit(1)} is inert and a parent with no related row sorts on {@code NULL}.
     */
    public static MethodSpec method(String name, OrderBySpec.SortKey.Path path) {
        List<JoinStep> steps = List.copyOf(path.hops());
        var aliases = PathFragments.generateAliases(steps);
        var body = CodeBlock.builder();
        for (int i = 0; i < steps.size(); i++) {
            var hop = path.hops().get(i);
            String previousAlias = i == 0 ? "table" : aliases.get(i - 1);
            // Path hops are key joins onto catalog tables (no routine node), so the registry the
            // routine arm would register a descent helper with is never reached.
            body.addStatement("$T $L = $L.as($L.getName() + $S)",
                CatalogRefs.tableClass(hop.targetTable()), aliases.get(i),
                PathFragments.emitTableExpression(hop, new PreviousNodeRef.TypedAlias(previousAlias),
                    new ArgumentValueSource.Env(), null),
                "table", "_" + aliases.get(i));
        }
        var subselect = new SelectTerm.ScalarSubselect(steps,
            new ParentCorrelation.OnFkSlots(path.hops().getFirst()), path.column());
        body.addStatement("return $T.field($L)", DSL, PathFragments.scalarInnerSelect(subselect, aliases, "table"));
        return MethodSpec.methodBuilder(name)
            .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
            .returns(ParameterizedTypeName.get(FIELD, WildcardTypeName.subtypeOf(Object.class)))
            .addParameter(CatalogRefs.tableClass(path.startTable()), "table")
            .addCode(body.build())
            .build();
    }
}
