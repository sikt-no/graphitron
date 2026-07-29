package no.sikt.graphitron.render;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.TableExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The join-path emission fragments that dispatch on a hop's occupants ({@link On},
 * {@link TableExpr}): alias derivation, table-expression materialization, the whole
 * bridging-hop fragments for both walk orders, and the two-argument condition-method call. The
 * proven-FK keyed fragments stay in {@link JoinFragments} (its entry points take a proven
 * {@link On.ColumnPairs}, never a raw {@link On}); these are the arms above that narrowing.
 * Moved here from the legacy join emitter when the projection renderer became their second
 * consumer; {@code JoinPathEmitter} delegates, so migrated and unmigrated hosts keep one
 * derivation.
 */
public final class PathFragments {

    private PathFragments() {}

    /**
     * Generates deterministic per-hop aliases from each step's target-table simple class name,
     * one alias per hop: first character lowercased + hop index ({@code "l0"} for
     * {@code Language}, {@code "c1"} for {@code Country} at index 1). When two hops in the same
     * chain share a first character, the later hop falls back to two lowercased characters
     * ({@code "fi0"} when {@code Film} and {@code FilmActor} collide).
     */
    public static List<String> generateAliases(List<JoinStep> path) {
        var aliases = new ArrayList<String>(path.size());
        var prefixCount = new HashMap<String, Integer>();
        for (int i = 0; i < path.size(); i++) {
            String javaName = ((JoinStep.HasTargetTable) path.get(i)).targetTable().tableClass().simpleName();
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
     * Emits the table expression a hop's alias declaration binds: the single materialization
     * switch on the hop's {@link TableExpr} target. Callers append {@code .as(alias)}. All
     * alias-declaration loops route through this helper so a new {@link TableExpr} arm forces
     * exactly one emit-side acknowledgment; {@link JoinStep.HasTargetTable#targetTable()} stays
     * the read for alias <em>naming</em> and terminus checks, never for materialization.
     *
     * @param step         the join step whose FROM/JOIN source is being declared
     * @param previousNode where correlated routine-call bindings read the chain's previous
     *                     node's columns
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
     * The whole bridging-hop join fragment for chains emitted start-first: one exhaustive
     * dispatch on the hop's {@link On} covering the keyed join
     * ({@link JoinFragments#emitForwardJoin}), the condition join
     * ({@code .join(hop).on(method(prev, hop))}), and the lateral routine hop
     * ({@code .crossJoin(DSL.lateral(hop))}, whose correlation rides the call arguments the
     * caller's alias declaration rendered). Callers supply their own surrounding whitespace.
     */
    public static CodeBlock emitForwardBridging(JoinStep.Hop hop, String prevAlias, String hopAlias) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> JoinFragments.emitForwardJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                hopAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> CodeBlock.of(".crossJoin($T.lateral($L))",
                ClassName.get("org.jooq.impl", "DSL"), hopAlias);
        };
    }

    /**
     * Terminal-first sibling of {@link #emitForwardBridging} for chains walked from the terminal
     * back towards step 0: the hop's own alias is already in scope, so the <em>previous</em>
     * node's alias is joined in. A lateral routine hop cannot appear on these paths (multi-node
     * routine chains classify as typed Deferred); {@code pathKindLabel} names the caller's path
     * family in the guard.
     */
    public static CodeBlock emitBackwardBridging(JoinStep.Hop hop, String prevAlias,
            String hopAlias, String pathKindLabel) {
        return switch (hop.on()) {
            case On.ColumnPairs cp -> JoinFragments.emitBridgingJoin(cp, prevAlias, hopAlias);
            case On.Predicate pred -> CodeBlock.of(".join($L).on($L)",
                prevAlias, emitTwoArgMethodCall(pred.condition(), prevAlias, hopAlias));
            case On.Lateral ignored -> throw new IllegalStateException(
                "a lateral routine hop cannot appear in a " + pathKindLabel + " path; "
                + "multi-node routine chains classify as typed Deferred");
        };
    }

    /**
     * Emits a {@code <className>.<methodName>(srcAlias, tgtAlias)} invocation used by
     * {@link JoinStep.Hop#filter()} (added to the enclosing WHERE) and by
     * {@link On.Predicate#condition()} (used as the join ON clause). Takes the
     * {@link JoinConditionRef} wrapper directly: the two-argument calling convention is the
     * wrapper's contract.
     */
    public static CodeBlock emitTwoArgMethodCall(JoinConditionRef condition, String srcAlias, String tgtAlias) {
        var method = condition.method();
        return CodeBlock.of("$T.$L($L, $L)",
            ClassName.bestGuess(method.className()), method.methodName(), srcAlias, tgtAlias);
    }
}
