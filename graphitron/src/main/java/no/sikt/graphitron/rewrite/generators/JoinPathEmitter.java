package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.HasSlots;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits the join chain and correlation predicates for a {@link JoinStep} list.
 *
 * <p>Shared by the inline correlated subquery (INNER JOIN), the inline-projection child-lookup
 * path (same shape with a VALUES+JOIN keyset), and the flat-batch fetcher (LEFT JOIN). The
 * INNER/LEFT choice is the caller's; this emitter uniformly produces {@code .join(...)} calls.
 * The caller also composes any correlation WHERE from step 0 against the parent alias (see
 * {@link #emitCorrelationWhere}).
 */
public final class JoinPathEmitter {

    private JoinPathEmitter() {}

    /**
     * Generates deterministic per-hop aliases from each step's target-table simple class name,
     * one alias per hop. Format: first character lowercased + hop index (e.g. {@code "l0"} for
     * {@code Language}, {@code "c1"} for {@code Country} at index 1). When two hops in the same
     * chain share a first character, the later hop falls back to two lowercased characters
     * ({@code "fi0"} when {@code Film} and {@code FilmActor} collide).
     *
     * <p>Callers that need the aliased table typed class name (for {@code Tables.X.as(alias)})
     * should separately read {@link TableRef#tableClass()} from each step.
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
        // Every JoinStep permit implements HasTargetTable (FkJoin and LiftedHop via WithTarget;
        // ConditionJoin directly), so the read is uniform without a sealed switch. The
        // terminalTable parameter is retained for the rare unit-test setup that constructs a
        // JoinStep without a resolved target; the parameter retires when those test setups update.
        return ((JoinStep.HasTargetTable) step).targetTable().tableClass().simpleName();
    }

    /**
     * Emits the correlation WHERE predicate relating the first-hop's target alias to the parent
     * alias: {@code first.alias().<slot.targetSide()> = parent.<slot.sourceSide()>} for each
     * slot, ANDed together. Direction-blind because each slot is oriented at synthesis time:
     * {@code sourceSide} is always the column on the source (parent) table and
     * {@code targetSide} the column on the target (first-hop) table, regardless of which end of
     * the catalog FK each maps to.
     *
     * <p>Composite FKs AND the paired columns. Single-column FKs are the common case; the slot
     * iteration supports both uniformly. Empty-slot fallback (jOOQ catalog unavailable at build
     * time) emits a runtime-throwing {@code DSL.noCondition()} stub so the mismatch surfaces at
     * execution rather than silently producing broken SQL.
     */
    public static CodeBlock emitCorrelationWhere(HasSlots first, String firstAlias,
            String parentAlias) {
        if (first.slotCount() == 0) {
            // No slots — jOOQ catalog was unavailable at build time. Emit a runtime-throwing
            // stub so the mismatch surfaces at execution rather than silently producing broken SQL.
            return CodeBlock.of("$T.noCondition()",
                ClassName.get("org.jooq.impl", "DSL"));
        }
        var code = CodeBlock.builder();
        int i = 0;
        for (var slot : first.slots()) {
            if (i > 0) code.add(".and(");
            code.add("$L.$L.eq($L.$L)",
                firstAlias, slot.targetSide().javaName(),
                parentAlias, slot.sourceSide().javaName());
            if (i > 0) code.add(")");
            i++;
        }
        return code.build();
    }

    /**
     * Emits a {@code <className>.<methodName>(srcAlias, tgtAlias)} invocation used by
     * {@link JoinStep.FkJoin#whereFilter()} (added to the enclosing WHERE) and by
     * {@link JoinStep.ConditionJoin#condition()} (used as the join ON clause). Takes the
     * {@link JoinConditionRef} wrapper directly — the two-argument calling convention is the
     * wrapper's contract, so call sites hand over the typed reference rather than extracting
     * a raw {@code MethodRef}.
     */
    public static CodeBlock emitTwoArgMethodCall(JoinConditionRef condition, String srcAlias, String tgtAlias) {
        var method = condition.method();
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(method.className()), method.methodName(), srcAlias, tgtAlias);
    }
}
