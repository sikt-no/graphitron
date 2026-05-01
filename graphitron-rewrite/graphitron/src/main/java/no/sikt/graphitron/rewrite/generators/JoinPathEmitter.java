package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.ColumnRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the join chain and correlation predicates for a {@link JoinStep} list.
 *
 * <p>Shared by G5 (inline correlated subquery, INNER JOIN), argres Phase 2a (same shape with a
 * VALUES+JOIN keyset), and G6 (LEFT JOIN flat batch). The INNER/LEFT choice is the caller's; this
 * emitter uniformly produces {@code .join(...)} calls. The caller also composes any correlation
 * WHERE from step 0 against the parent alias (see {@link #emitCorrelationWhere}).
 */
public final class JoinPathEmitter {

    private JoinPathEmitter() {}

    /**
     * Generates deterministic per-hop aliases from each step's target-table {@code javaClassName},
     * one alias per hop. Format: first character lowercased + hop index (e.g. {@code "l0"} for
     * {@code Language}, {@code "c1"} for {@code Country} at index 1). When two hops in the same
     * chain share a first character, the later hop falls back to two lowercased characters
     * ({@code "fi0"} when {@code Film} and {@code FilmActor} collide).
     *
     * <p>Callers that need the aliased table typed class name (for {@code Tables.X.as(alias)})
     * should separately read {@link TableRef#javaClassName()} from each step.
     *
     * @param path           the full join path
     * @param terminalTable  terminal target table (needed when the last step is a condition-join
     *                       with no pre-resolved target; pass {@code null} otherwise)
     */
    public static List<String> generateAliases(List<JoinStep> path, TableRef terminalTable) {
        var aliases = new ArrayList<String>(path.size());
        var prefixCount = new HashMap<String, Integer>();
        for (int i = 0; i < path.size(); i++) {
            String javaName = targetJavaClassName(path.get(i), i, path.size(), terminalTable);
            String basePrefix = javaName.isEmpty() ? "t" : javaName.substring(0, 1).toLowerCase();
            int occurrence = prefixCount.merge(basePrefix, 1, Integer::sum);
            String prefix = occurrence == 1 || javaName.length() < 2
                ? basePrefix
                : javaName.substring(0, 2).toLowerCase();
            aliases.add(prefix + i);
        }
        return aliases;
    }

    private static String targetJavaClassName(JoinStep step, int index, int size, TableRef terminalTable) {
        return switch (step) {
            case JoinStep.FkJoin fk -> fk.targetTable().javaClassName();
            case JoinStep.LiftedHop lh -> lh.targetTable().javaClassName();
            case JoinStep.ConditionJoin cj ->
                // ConditionJoin does not carry a resolved TableRef (see classification-vocab item 5).
                // When this is the terminal step and the caller knows the terminal table, use it;
                // otherwise we cannot emit a usable alias and the caller must branch.
                (index == size - 1 && terminalTable != null) ? terminalTable.javaClassName() : "";
        };
    }

    /**
     * Returns {@code true} when the path contains any {@link JoinStep.ConditionJoin}. G5 cannot
     * yet emit a correlated subquery for such paths (the target table is not pre-resolved — see
     * classification-vocabulary item 5). The emitter branches on this to emit a runtime-throwing
     * stub instead.
     */
    public static boolean hasConditionJoin(List<JoinStep> path) {
        return path.stream().anyMatch(s -> s instanceof JoinStep.ConditionJoin);
    }

    /**
     * Emits the correlation WHERE predicate relating the first-hop's target alias to the parent
     * alias. Branches on FK direction: if the parent holds the FK columns,
     * {@code first.alias().<targetCol> = parent.<sourceCol>} (matched by position); otherwise
     * {@code first.alias().<sourceCol> = parent.<targetCol>}.
     *
     * <p>The {@code parentHoldsFk} flag is supplied by the caller from the field's cardinality:
     * {@code Single} cardinality means the parent row has at most one matching target — i.e. the
     * parent holds the FK. {@code List} cardinality means the parent is the PK side and many
     * target rows may hold the FK pointing back. Cardinality is the reliable signal even for
     * self-referential FKs where source and target tables are identical (both {@code parent} and
     * {@code children} fields navigate the same FK, in opposite directions).
     *
     * <p>Composite FKs AND the paired columns. Single-column FKs are the common case; the zip
     * supports both uniformly. Precondition: {@code sourceColumns.size() == targetColumns.size()}
     * (jOOQ guarantees equal arity; a mismatch is a builder bug and must fail loudly).
     */
    public static CodeBlock emitCorrelationWhere(JoinStep.FkJoin first, String firstAlias,
            String parentAlias, boolean parentHoldsFk) {
        if (first.sourceColumns().size() != first.targetColumns().size()) {
            throw new IllegalStateException(
                "FkJoin '" + first.fkName() + "': sourceColumns/targetColumns arity mismatch ("
                + first.sourceColumns().size() + " vs " + first.targetColumns().size() + ")");
        }
        List<ColumnRef> innerCols = parentHoldsFk ? first.targetColumns() : first.sourceColumns();
        List<ColumnRef> parentCols = parentHoldsFk ? first.sourceColumns() : first.targetColumns();
        if (innerCols.isEmpty() || parentCols.isEmpty()) {
            // Empty column refs — jOOQ catalog was unavailable at build time. Emit a
            // runtime-throwing stub so the mismatch surfaces at execution rather than silently
            // producing broken SQL.
            return CodeBlock.of("$T.noCondition()",
                ClassName.get("org.jooq.impl", "DSL"));
        }
        var code = CodeBlock.builder();
        for (int i = 0; i < innerCols.size(); i++) {
            if (i > 0) code.add(".and(");
            code.add("$L.$L.eq($L.$L)",
                firstAlias, innerCols.get(i).javaName(),
                parentAlias, parentCols.get(i).javaName());
            if (i > 0) code.add(")");
        }
        return code.build();
    }

    /**
     * Emits a {@code <className>.<methodName>(srcAlias, tgtAlias)} invocation used by
     * {@link JoinStep.FkJoin#whereFilter()} (added to the enclosing WHERE) and by
     * {@link JoinStep.ConditionJoin#condition()} (used as the join ON clause).
     */
    public static CodeBlock emitTwoArgMethodCall(MethodRef method, String srcAlias, String tgtAlias) {
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(method.className()), method.methodName(), srcAlias, tgtAlias);
    }
}
