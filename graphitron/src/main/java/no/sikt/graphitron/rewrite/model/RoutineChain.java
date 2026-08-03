package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The {@code (start, hops)} shape of a root routine chain: {@code start} is the
 * routine node (the schema's global {@code Routines} convenience-method call with IN parameters
 * bound from GraphQL arguments), {@code hops} the {@code @reference}-contributed steps that follow
 * it in authored directive order. The single-node shape is {@code hops = []}, where the
 * routine result is also the terminus.
 *
 * <p>Shared invariant enforcer for the two carriers: the root read's
 * {@link RoutineResolution.Chain} source arm and the mutation write leaf
 * ({@link MutationField.MutationRoutineWriteField}). Each carrier adds its own invariants on
 * top: the read side's terminus rule against the leaf's return type (in
 * {@link QueryField.QueryTableField}'s constructor), the write leaf's non-empty {@code hops}
 * and column-pairs hop 0.
 *
 * <p>The start-binding guard (every routine parameter bound from a {@link ParamSource.Arg}) is
 * what lets the shared {@link no.sikt.graphitron.render.RoutineCallEmitter} path
 * assume {@link no.sikt.graphitron.render.PreviousNodeRef.None} carries no
 * {@link ParamSource.SourceColumn} read.
 */
public record RoutineChain(TableExpr.RoutineCall start, List<JoinStep> hops) {

    public RoutineChain {
        if (start == null) {
            throw new NullPointerException("RoutineChain.start must not be null");
        }
        for (RoutineRef.ArgBinding binding : start.routine().argBindings()) {
            if (!(binding.source() instanceof ParamSource.Arg)) {
                throw new IllegalArgumentException(
                    "RoutineChain start binding for routine parameter '"
                    + binding.routineParamName() + "' carries "
                    + binding.source().getClass().getSimpleName()
                    + "; a root routine chain's head has no previous node, so every start "
                    + "binding must be ParamSource.Arg (RoutineDirectiveResolver rejects "
                    + "columnMapping at root before construction)");
            }
        }
        hops = List.copyOf(hops);
        for (JoinStep step : hops) {
            if (!(step instanceof JoinStep.Hop hop)) {
                throw new IllegalArgumentException(
                    "RoutineChain.hops must be @reference-contributed Hops; got "
                    + step.getClass().getSimpleName());
            }
            if (!(hop.target() instanceof TableExpr.Catalog) || hop.on() instanceof On.Lateral) {
                throw new IllegalArgumentException(
                    "RoutineChain admits exactly one routine node, the chain's start; a routine "
                    + "node at hop position (a multi-routine chain) classifies as typed Deferred "
                    + "and must not reach this carrier");
            }
        }
    }

    /** The routine call surface of the chain's start node. */
    public RoutineRef routine() {
        return start.routine();
    }

    /** The chain's last node: the last hop's target, or the routine result when {@code hops} is empty. */
    public TableRef terminus() {
        return hops.isEmpty()
            ? start.resultTable()
            : ((JoinStep.Hop) hops.getLast()).targetTable();
    }
}
