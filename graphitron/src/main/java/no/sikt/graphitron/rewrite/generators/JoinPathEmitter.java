package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.HasSlots;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.TableExpr;
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
        // Every JoinStep permit implements HasTargetTable, so the read is uniform without a
        // sealed switch. The
        // terminalTable parameter is retained for the rare unit-test setup that constructs a
        // JoinStep without a resolved target; the parameter retires when those test setups update.
        return ((JoinStep.HasTargetTable) step).targetTable().tableClass().simpleName();
    }

    /**
     * Emits the table expression a hop's alias declaration binds — the single materialization
     * switch on the hop's {@link TableExpr} target (R435). Callers append {@code .as(alias)}.
     * All alias-declaration loops route through this helper so a new {@link TableExpr} arm
     * forces exactly one emit-side acknowledgment; {@link JoinStep.HasTargetTable#targetTable()}
     * stays the read for alias <em>naming</em> and terminus checks, never for materialization.
     *
     * @param step              the join step whose FROM/JOIN source is being declared
     * @param previousNodeAlias the in-scope alias of the chain's previous node (the parent
     *                          alias at hop 0), read by correlated routine-call bindings
     * @param argSource         where argument-sourced routine bindings read runtime values
     */
    public static CodeBlock emitTableExpression(JoinStep step, String previousNodeAlias,
            ArgumentValueSource argSource) {
        return switch (step) {
            case JoinStep.Hop hop -> switch (hop.target()) {
                case TableExpr.Catalog c -> CodeBlock.of("$T.$L",
                    c.table().constantsClass(), c.table().javaFieldName());
                case TableExpr.RoutineCall rc ->
                    RoutineCallEmitter.emitCall(rc, previousNodeAlias, argSource);
            };
            // Transitional (retires with R431): the lifter shape's target is always a catalog
            // table; LiftedHop carries no TableExpr axis.
            case JoinStep.LiftedHop lh -> CodeBlock.of("$T.$L",
                lh.targetTable().constantsClass(), lh.targetTable().javaFieldName());
        };
    }

    /**
     * Emits the join-in of a bridging hop's origin alias with its ON clause — the single
     * dispatch on {@link On.Keying} (R435). {@link On.Keying.ForeignKey} emits the legible
     * {@code .join(prev).onKey(Keys.<FK>)}; {@link On.Keying.NameMatchedKey} has no {@code Keys}
     * constant, so it emits the explicit column-equality conjunction over the pairs'
     * {@code slots}: {@code .join(prev).on(prev.<sourceSide>.eq(hop.<targetSide>))...}.
     *
     * <p>All bridging-join emit sites route through this helper so a new {@link On.Keying} arm
     * forces exactly one emit-side acknowledgment. Callers supply their own surrounding
     * whitespace / line-break formatting.
     *
     * @param cp        the hop's column pairs
     * @param prevAlias the previous node's alias (the hop's origin side, being joined in)
     * @param hopAlias  the hop's own alias (already in scope in the enclosing FROM/JOIN chain)
     */
    public static CodeBlock emitBridgingJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return emitKeyedJoin(cp, /*joinedAlias=*/prevAlias, prevAlias, hopAlias);
    }

    /**
     * Forward-order sibling of {@link #emitBridgingJoin} for chains emitted start-first (the
     * root routine chain's fetcher, R435): the FROM clause holds the chain's start, so each hop
     * joins its <em>own</em> alias in — {@code .join(hop)} — with the same keying-dispatched ON.
     */
    public static CodeBlock emitForwardJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return emitKeyedJoin(cp, /*joinedAlias=*/hopAlias, prevAlias, hopAlias);
    }

    private static CodeBlock emitKeyedJoin(On.ColumnPairs cp, String joinedAlias,
            String prevAlias, String hopAlias) {
        return switch (cp.keying()) {
            case On.Keying.ForeignKey k -> CodeBlock.of(".join($L).onKey($T.$L)",
                joinedAlias, k.fk().keysClass(), k.fk().constantName());
            case On.Keying.NameMatchedKey ignored -> {
                if (cp.slotCount() == 0) {
                    throw new IllegalStateException(
                        "a name-matched-key hop with no slots cannot be emitted; the derivation "
                        + "mints the pairs from the live catalog, so empty slots indicate a "
                        + "classifier bug, not a missing catalog");
                }
                var on = CodeBlock.builder();
                int i = 0;
                for (var slot : cp.slots()) {
                    if (i > 0) on.add(".and(");
                    on.add("$L.$L.eq($L.$L)",
                        prevAlias, slot.sourceSide().javaName(),
                        hopAlias, slot.targetSide().javaName());
                    if (i > 0) on.add(")");
                    i++;
                }
                yield CodeBlock.of(".join($L).on($L)", joinedAlias, on.build());
            }
        };
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
     * {@link JoinStep.Hop#filter()} (added to the enclosing WHERE) and by
     * {@link no.sikt.graphitron.rewrite.model.On.Predicate#condition()} (used as the join ON
     * clause). Takes the
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
