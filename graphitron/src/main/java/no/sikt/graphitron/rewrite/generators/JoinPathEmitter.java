package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.CodeBlock;
import no.sikt.graphitron.render.ArgumentValueSource;
import no.sikt.graphitron.render.PathFragments;
import no.sikt.graphitron.render.PreviousNodeRef;
import no.sikt.graphitron.rewrite.model.JoinConditionRef;
import no.sikt.graphitron.rewrite.model.JoinStep;
import no.sikt.graphitron.rewrite.model.On;
import no.sikt.graphitron.rewrite.model.TableRef;

import java.util.List;

/**
 * Emits the join chain and correlation predicates for a {@link JoinStep} list.
 *
 * <p>The emission itself lives in {@link no.sikt.graphitron.render.PathFragments} and
 * {@link no.sikt.graphitron.render.JoinFragments} (the projection and condition renderers read
 * the same fragments); these entry points survive for the unmigrated hosts, so migrated and
 * unmigrated emission keeps one derivation. The INNER/LEFT choice is the caller's; the fragments
 * uniformly produce {@code .join(...)} calls, and the caller composes any correlation WHERE from
 * step 0 against the parent alias (see {@link #emitCorrelationWhere}).
 */
public final class JoinPathEmitter {

    private JoinPathEmitter() {}

    /**
     * Generates deterministic per-hop aliases from each step's target-table simple class name;
     * see {@link PathFragments#generateAliases}.
     *
     * @param path           the full join path
     * @param terminalTable  unused (every {@link JoinStep} permit carries its own target table);
     *                       pass {@code null}
     */
    public static List<String> generateAliases(List<JoinStep> path, TableRef terminalTable) {
        return PathFragments.generateAliases(path);
    }

    /**
     * The single synthesized alias for the hop-less
     * {@link no.sikt.graphitron.rewrite.model.ParentCorrelation.OnLiftedSlots} shape: the same
     * derivation {@link #generateAliases} applies to a single-hop path, i.e. the target table's
     * simple class name, first character lowercased, suffixed with hop index {@code 0}
     * (e.g. {@code "f0"} for {@code Film}).
     */
    public static String liftedAlias(TableRef targetTable) {
        return PathFragments.liftedAlias(targetTable);
    }

    /** See {@link PathFragments#emitTableExpression}. */
    public static CodeBlock emitTableExpression(JoinStep step, PreviousNodeRef previousNode,
            ArgumentValueSource argSource,
            no.sikt.graphitron.render.ArgPathHelperRegistry argHelpers) {
        return PathFragments.emitTableExpression(step, previousNode, argSource, argHelpers);
    }

    /** See {@link no.sikt.graphitron.render.JoinFragments#emitBridgingJoin}. */
    public static CodeBlock emitBridgingJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return no.sikt.graphitron.render.JoinFragments.emitBridgingJoin(cp, prevAlias, hopAlias);
    }

    /** See {@link no.sikt.graphitron.render.JoinFragments#emitForwardJoin}. */
    public static CodeBlock emitForwardJoin(On.ColumnPairs cp, String prevAlias, String hopAlias) {
        return no.sikt.graphitron.render.JoinFragments.emitForwardJoin(cp, prevAlias, hopAlias);
    }

    /** See {@link PathFragments#emitForwardBridging}. */
    public static CodeBlock emitForwardBridging(JoinStep.Hop hop, String prevAlias, String hopAlias) {
        return PathFragments.emitForwardBridging(hop, prevAlias, hopAlias);
    }

    /** See {@link PathFragments#emitBackwardBridging}. */
    public static CodeBlock emitBackwardBridging(JoinStep.Hop hop, String prevAlias,
            String hopAlias, String pathKindLabel) {
        return PathFragments.emitBackwardBridging(hop, prevAlias, hopAlias, pathKindLabel);
    }

    /** See {@link no.sikt.graphitron.render.JoinFragments#emitCorrelationWhere}. */
    public static CodeBlock emitCorrelationWhere(On.ColumnPairs first, String firstAlias,
            String parentAlias) {
        return no.sikt.graphitron.render.JoinFragments.emitCorrelationWhere(first, firstAlias, parentAlias);
    }

    /** See {@link PathFragments#emitTwoArgMethodCall}. */
    public static CodeBlock emitTwoArgMethodCall(JoinConditionRef condition, String srcAlias, String tgtAlias) {
        return PathFragments.emitTwoArgMethodCall(condition, srcAlias, tgtAlias);
    }
}
