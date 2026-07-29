package no.sikt.graphitron.render;

import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * The shared {@code $project} call-expression emitter every projection consumer reads: the
 * projection renderer's own nested descents and the not-yet-migrated launcher hosts (root and
 * batched fetchers, the polymorphic per-participant selects, the federation entity dispatch)
 * all compose the call through these three shapes, so the call convention has one derivation
 * across the migration window and the launcher family has one place to repoint.
 *
 * <p>One emitted method per projection unit, grouped selection in
 * ({@code Map<String, List<SelectedField>>}) and select list out; the two selection adapters
 * ({@code getFieldsGroupedByResultKey()} on a selection set, {@code mergeByResultKey} on an
 * occurrence bucket) compose at the call site and converge on the same callee input. The
 * {@code env} argument is the enclosing fetcher's environment, in scope as {@code env} at every
 * consumer, threaded for request-scoped context reads only.
 */
public final class ProjectionCall {

    /**
     * The projection method's name. The {@code $} prefix can never collide with a GraphQL field
     * name ({@code /[_A-Za-z][_0-9A-Za-z]*&#47;} by spec).
     */
    public static final String METHOD_NAME = "$project";

    private ProjectionCall() {}

    /** {@code <Unit>.$project(env.getSelectionSet().getFieldsGroupedByResultKey(), <table>, env)}. */
    public static CodeBlock fromEnvSelection(ClassName unitClass, String tableLocal) {
        return fromSelectionSet(unitClass, CodeBlock.of("env.getSelectionSet()"),
            CodeBlock.of("$L", tableLocal));
    }

    /** {@code <Unit>.$project(<sel>.getFieldsGroupedByResultKey(), <table>, env)}. */
    public static CodeBlock fromSelectionSet(ClassName unitClass, CodeBlock selectionSetExpr,
            CodeBlock tableExpr) {
        return CodeBlock.of("$T.$L($L.getFieldsGroupedByResultKey(), $L, env)",
            unitClass, METHOD_NAME, selectionSetExpr, tableExpr);
    }

    /** {@code <Unit>.$project(SelectionOccurrences.mergeByResultKey(<occurrences>), <table>, env)}. */
    public static CodeBlock fromOccurrences(UnitRef unit, CodeBlock occurrencesExpr, CodeBlock tableExpr,
            String outputPackage) {
        return CodeBlock.of("$T.$L($T.mergeByResultKey($L), $L, env)",
            className(unit), METHOD_NAME, selectionOccurrencesClass(outputPackage), occurrencesExpr, tableExpr);
    }

    /** {@code <Unit>.$project(<grouped>, <table>, env)} for an already-grouped map expression. */
    public static CodeBlock fromGrouped(ClassName unitClass, CodeBlock groupedExpr, CodeBlock tableExpr) {
        return CodeBlock.of("$T.$L($L, $L, env)",
            unitClass, METHOD_NAME, groupedExpr, tableExpr);
    }

    /** The generated {@code <outputPackage>.util.SelectionOccurrences} runtime scaffold. */
    static ClassName selectionOccurrencesClass(String outputPackage) {
        return ClassName.get(outputPackage + ".util", "SelectionOccurrences");
    }

    private static ClassName className(UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
