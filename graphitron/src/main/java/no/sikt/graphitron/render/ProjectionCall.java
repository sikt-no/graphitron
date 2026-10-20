package no.sikt.graphitron.render;

import no.sikt.graphitron.command.LauncherCommand;
import no.sikt.graphitron.command.ResultShape;
import no.sikt.graphitron.command.UnitRef;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;

/**
 * The shared {@code $project} call-expression emitter every projection consumer reads: the
 * projection renderer's own nested descents and the launcher hosts (root and batched fetchers,
 * the polymorphic per-participant selects, the federation entity dispatch) all compose the call
 * through these shapes, so the call convention has one derivation and the launcher family has
 * one place to repoint.
 *
 * <p>One emitted method per projection unit, grouped selection in
 * ({@code Map<String, List<SelectedField>>}) and select list out. Every grouped map is built by
 * the generated {@code SelectionOccurrences} adapters, which group immediate children only: a
 * launcher reads the fetched field's selection through {@link SelectionScope} (the field's own
 * children, or the node level of a connection), and a nested descent reads one result-key
 * bucket through {@code mergeByResultKey}. The {@code env} argument is the enclosing fetcher's
 * environment, in scope as {@code env} at every consumer, threaded for request-scoped context
 * reads only.
 */
public final class ProjectionCall {

    /**
     * The projection method's name. The {@code $} prefix can never collide with a GraphQL field
     * name ({@code /[_A-Za-z][_0-9A-Za-z]*&#47;} by spec).
     */
    public static final String METHOD_NAME = "$project";

    /**
     * Where a launcher's unit fields sit relative to the fetched field, derived from what the
     * launcher returns rather than chosen per call site: a connection's unit fields sit at its
     * node level ({@code edges.node} and {@code nodes}, merged), every other launcher's are the
     * fetched field's immediate children. A connection launcher reading the immediate children
     * would see only {@code edges} / {@code nodes} / {@code pageInfo}, match no arm, and silently
     * project the row-present sentinel, which is why the scope is never spelled by hand.
     */
    public enum SelectionScope {
        OWN_FIELDS("ownFields"),
        CONNECTION_NODES("connectionNodes");

        private final String adapter;

        SelectionScope(String adapter) {
            this.adapter = adapter;
        }

        /** The scope a launcher returning {@code shape} reads its unit's fields at. */
        public static SelectionScope of(ResultShape shape) {
            return of(shape instanceof ResultShape.Connection);
        }

        /**
         * The scope for an emitter that holds no {@link ResultShape} but already branches on the
         * connection fact.
         */
        public static SelectionScope of(boolean connection) {
            return connection ? CONNECTION_NODES : OWN_FIELDS;
        }
    }

    private ProjectionCall() {}

    /**
     * {@code <Unit>.$project(SelectionOccurrences.<scope>(env.getSelectionSet()), <table>, env)}
     * for a launcher row: the scaffold rides the command and the scope is the one its result
     * entails.
     */
    public static CodeBlock fromLauncher(ClassName unitClass, String tableLocal, LauncherCommand row) {
        return fromEnvSelection(unitClass, tableLocal,
            className(row.selectionOccurrences()), SelectionScope.of(row.result()));
    }

    /**
     * {@code <Unit>.$project(SelectionOccurrences.<scope>(env.getSelectionSet()), <table>, env)}:
     * the entry for an emitter that holds the scaffold's class and the scope itself.
     */
    public static CodeBlock fromEnvSelection(ClassName unitClass, String tableLocal,
            ClassName selectionOccurrences, SelectionScope scope) {
        return fromGrouped(unitClass, envSelection(selectionOccurrences, scope),
            CodeBlock.of("$L", tableLocal));
    }

    /**
     * {@code SelectionOccurrences.<scope>(env.getSelectionSet())}: the fetched field's selection,
     * grouped at the level its unit fields sit at.
     */
    public static CodeBlock envSelection(ClassName selectionOccurrences, SelectionScope scope) {
        return CodeBlock.of("$T.$L(env.getSelectionSet())", selectionOccurrences, scope.adapter);
    }

    /** {@code <Unit>.$project(SelectionOccurrences.mergeByResultKey(<occurrences>), <table>, env)}. */
    public static CodeBlock fromOccurrences(UnitRef unit, CodeBlock occurrencesExpr, CodeBlock tableExpr,
            String outputPackage) {
        return CodeBlock.of("$T.$L($T.mergeByResultKey($L), $L, env)",
            className(unit), METHOD_NAME, selectionOccurrencesClass(outputPackage), occurrencesExpr, tableExpr);
    }

    /**
     * {@code <Unit>.$project(SelectionOccurrences.mergeByResultKey(<occurrences>), <table>, env,
     * <rkPath>)}: the splice into a nested unit, which takes the alias path of the row it writes
     * into.
     */
    public static CodeBlock fromOccurrences(UnitRef unit, CodeBlock occurrencesExpr, CodeBlock tableExpr,
            CodeBlock rkPathExpr, String outputPackage) {
        return CodeBlock.of("$T.$L($T.mergeByResultKey($L), $L, env, $L)",
            className(unit), METHOD_NAME, selectionOccurrencesClass(outputPackage), occurrencesExpr,
            tableExpr, rkPathExpr);
    }

    /** {@code <Unit>.$project(<grouped>, <table>, env)} for an already-grouped map expression. */
    public static CodeBlock fromGrouped(ClassName unitClass, CodeBlock groupedExpr, CodeBlock tableExpr) {
        return CodeBlock.of("$T.$L($L, $L, env)",
            unitClass, METHOD_NAME, groupedExpr, tableExpr);
    }

    /** The generated {@code <outputPackage>.util.SelectionOccurrences} runtime scaffold. */
    public static ClassName selectionOccurrencesClass(String outputPackage) {
        return ClassName.get(outputPackage + ".util", "SelectionOccurrences");
    }

    static ClassName className(UnitRef unit) {
        return ClassName.get(unit.packageName(), unit.simpleName());
    }
}
