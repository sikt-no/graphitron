package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
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
     * @param terminalTable  unused (every {@link JoinStep} permit carries its own target table);
     *                       pass {@code null}
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

    /**
     * The single synthesized alias for the hop-less
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots} shape: the same
     * derivation {@link #generateAliases} applies to a single-hop path, i.e. the target table's
     * simple class name, first character lowercased, suffixed with hop index {@code 0}
     * (e.g. {@code "f0"} for {@code Film}).
     */
    public static String liftedAlias(TableRef targetTable) {
        String javaName = targetTable.tableClass().simpleName();
        String basePrefix = javaName.isEmpty() ? "t" : javaName.substring(0, 1).toLowerCase();
        return basePrefix + 0;
    }

    private static String targetJavaClassName(JoinStep step, int index, int size, TableRef terminalTable) {
        // Every JoinStep permit implements HasTargetTable, so the read is uniform without a
        // sealed switch; the index, size, and terminalTable parameters are unused.
        return ((JoinStep.HasTargetTable) step).targetTable().tableClass().simpleName();
    }

    /**
     * Emits the table expression a hop's alias declaration binds: the single materialization
     * switch on the hop's {@link TableExpr} target. Callers append {@code .as(alias)}.
     * All alias-declaration loops route through this helper so a new {@link TableExpr} arm
     * forces exactly one emit-side acknowledgment; {@link JoinStep.HasTargetTable#targetTable()}
     * stays the read for alias <em>naming</em> and terminus checks, never for materialization.
     *
     * @param step         the join step whose FROM/JOIN source is being declared
     * @param previousNode where correlated routine-call bindings read the chain's previous
     *                     node's columns (the parent alias at an inline hop 0, the
     *                     {@code parentInput} lookup at a batched chain head)
     * @param argSource    where argument-sourced routine bindings read runtime values
     */
    public static CodeBlock emitTableExpression(JoinStep step, PreviousNodeRef previousNode,
            ArgumentValueSource argSource) {
        return switch (step) {
            case JoinStep.Hop hop -> switch (hop.target()) {
                case TableExpr.Catalog c -> CodeBlock.of("$T.$L",
                    c.table().constantsClass(), c.table().javaFieldName());
                case TableExpr.RoutineCall rc ->
                    RoutineCallEmitter.emitCall(rc, previousNode, argSource);
            };
        };
    }

    /**
     * Emits the join-in of a bridging hop's origin alias with its ON clause: the single
     * dispatch on {@link On.Keying}. {@link On.Keying.ForeignKey} emits the legible
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
     * root routine chain's fetcher): the FROM clause holds the chain's start, so each hop
     * joins its <em>own</em> alias in ({@code .join(hop)}) with the same keying-dispatched ON.
     */
    public static CodeBlock emitForwardJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return emitKeyedJoin(cp, /*joinedAlias=*/hopAlias, prevAlias, hopAlias);
    }

    /**
     * The whole bridging-hop join fragment for chains emitted start-first: one exhaustive
     * dispatch on the hop's {@link On} covering the
     * keyed join ({@link #emitForwardJoin}), the condition join
     * ({@code .join(hop).on(method(prev, hop))}), and the lateral routine hop
     * ({@code .crossJoin(DSL.lateral(hop))}, whose correlation rides the call arguments the
     * caller's alias declaration rendered). Callers supply their own surrounding whitespace,
     * as with the keyed helpers above. Shared by the inline table-field subquery and the
     * split-rows bridging loop, the two sites whose paths can carry all three arms.
     */
    public static CodeBlock emitForwardBridging(JoinStep.Hop hop, String prevAlias, String hopAlias) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> emitForwardJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                hopAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> CodeBlock.of(".crossJoin($T.lateral($L))",
                ClassName.get("org.jooq.impl", "DSL"), hopAlias);
        };
    }

    /**
     * Terminal-first sibling of {@link #emitForwardBridging} for chains walked from the
     * terminal back towards step 0 (the inline lookup and column-reference subqueries): the
     * hop's own alias is already in scope, so the <em>previous</em> node's alias is joined in
     * ({@link #emitBridgingJoin} / {@code .join(prev).on(method(prev, hop))}). A lateral
     * routine hop cannot appear on these paths (multi-node routine chains classify as typed
     * Deferred); {@code pathKindLabel} names the caller's path family in the guard.
     */
    public static CodeBlock emitBackwardBridging(JoinStep.Hop hop, String prevAlias,
            String hopAlias, String pathKindLabel) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> emitBridgingJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                prevAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> throw new IllegalStateException(
                "a lateral routine hop cannot appear in a " + pathKindLabel + " path; "
                + "multi-node routine chains classify as typed Deferred");
        };
    }

    private static CodeBlock emitKeyedJoin(On.ColumnPairs cp, String joinedAlias,
            String prevAlias, String hopAlias) {
        return switch (cp.keying()) {
            case On.Keying.ForeignKey k -> CodeBlock.of(".join($L).onKey($T.$L)",
                joinedAlias, k.fk().keysClass(), k.fk().constantName());
            case On.Keying.NameMatchedKey ignored -> {
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
     * the catalog FK each maps to. Slots are never empty; {@link On.ColumnPairs} rejects the
     * degenerate shape at construction.
     */
    public static CodeBlock emitCorrelationWhere(On.ColumnPairs first, String firstAlias,
            String parentAlias) {
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
     * clause). Takes the {@link JoinConditionRef} wrapper directly: the two-argument calling
     * convention is the wrapper's contract, so call sites hand over the typed reference rather
     * than extracting a raw {@code MethodRef}.
     */
    public static CodeBlock emitTwoArgMethodCall(JoinConditionRef condition, String srcAlias, String tgtAlias) {
        var method = condition.method();
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(method.className()), method.methodName(), srcAlias, tgtAlias);
    }
}
