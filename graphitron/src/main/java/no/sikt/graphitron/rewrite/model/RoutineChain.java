package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The {@code (start, hops)} shape of a root routine chain: {@code start} is the
 * routine node (the schema's global {@code Routines} convenience-method call with IN parameters
 * bound from GraphQL arguments), {@code hops} the {@code @reference}-contributed steps that follow
 * it in authored directive order. The single-node shape is {@code hops = []}, where the
 * routine result is also the terminus.
 *
 * <p>Extracted from {@code QueryField.QueryRoutineTableField}'s compact constructor so the
 * read leaf and the mutation write leaf ({@code MutationField.MutationRoutineWriteField}) share
 * one enforcer for the chain invariants instead of duplicating them; both embed this record and
 * expose it through the {@link RoutineChainField} capability interface. A chain-wide invariant
 * change (for example a future {@code DataType} lift) edits this constructor once.
 *
 * <p>Invariants pinned here (the shared set; each leaf adds its own — the read leaf's terminus
 * rule against its return type, the write leaf's non-empty {@code hops}):
 * <ul>
 *   <li>{@code start} is non-null and binds every routine parameter from a GraphQL argument
 *       ({@link ParamSource.Arg}): a root chain's head has no previous node, so
 *       {@code RoutineDirectiveResolver} rejects {@code columnMapping} at root and mints only
 *       {@code Arg} bindings. Pinning the acceptance at the producer is what lets the shared
 *       {@code RoutineCallEmitter} path assume {@code PreviousNodeRef.None} carries no
 *       {@link ParamSource.SourceColumn} read.</li>
 *   <li>Every hop is an {@code @reference}-contributed {@link JoinStep.Hop} over a
 *       {@link TableExpr.Catalog} target, never {@link On.Lateral}: the chain's one routine node
 * is the start — a second routine node classifies as typed {@code Deferred} and must
 *       not reach this carrier.</li>
 * </ul>
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
                    + "(R435) and must not reach this carrier");
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
