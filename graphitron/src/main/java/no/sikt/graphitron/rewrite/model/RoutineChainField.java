package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Capability interface for the two root routine-chain leaves —
 * {@code QueryField.QueryRoutineTableField} (the R435 read) and
 * {@code MutationField.MutationRoutineWriteField} (the R451 write) — exposing the shared
 * {@link RoutineChain} carrier so the emission surface ({@code RoutineCallEmitter} /
 * {@code JoinPathEmitter} call sites in {@code TypeFetcherGenerator}) reads the chain off one
 * accessor spanning both roots rather than either concrete leaf.
 *
 * <p>Follows the {@link ServiceField} precedent: a plain (non-sealed) interface, sibling to the
 * sealed root hierarchies, declared in each implementor's {@code implements} clause. The chain
 * invariants live in {@link RoutineChain}'s compact constructor, not here; this interface only
 * names the shared read surface.
 */
public interface RoutineChainField {

    /** The field's {@code (start, hops)} routine chain. */
    RoutineChain chain();

    /** The chain's start node — see {@link RoutineChain#start()}. */
    default TableExpr.RoutineCall start() {
        return chain().start();
    }

    /** The chain's {@code @reference}-contributed hops — see {@link RoutineChain#hops()}. */
    default List<JoinStep> hops() {
        return chain().hops();
    }

    /** The routine call surface of the chain's start node — see {@link RoutineChain#routine()}. */
    default RoutineRef routine() {
        return chain().routine();
    }
}
