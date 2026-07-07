package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * How a {@link JoinStep.Hop} joins to its target — the <em>on</em> axis of the two-axis step
 * model (R333). Orthogonal to the target node itself ({@link TableExpr}): any target can in
 * principle be joined by either arm.
 *
 * <p>Non-null on every shipped hop, and it stays that way: R333 models {@code on} as absent
 * exactly for the path's start node, but the shipped path representation has no start-node entry
 * (the source supplies the start; {@code path[0]} already joins). When a start-node entry
 * arrives (R435's root routine node), it lands as its own sealed sibling or a
 * {@code (start, List<Hop>)} path carrier — never by widening {@code Hop.on} to
 * {@code Optional<On>}, which would reintroduce a null-in-exactly-one-case state and forfeit the
 * every-hop-joins certainty the non-null component buys.
 *
 * <p>A new way to join is a new arm here (e.g. R435's {@code Lateral} for routine targets, or a
 * PK/UK name-match derivation), not a new step type. When the name-match derivation lands, the
 * FK-vs-derived question becomes its own small seal on {@link ColumnPairs} and
 * {@link ColumnPairs#fk()} becomes one case of it.
 */
public sealed interface On permits On.ColumnPairs, On.Predicate {

    /**
     * The step joins on paired source / target columns derived from a foreign key.
     *
     * <p>{@code fk} is the resolved {@link ForeignKeyRef} the pairs were derived from — required
     * provenance, not redundancy: emitters emit the join as {@code .onKey(Keys.<FK>)} via
     * {@code fk.keysClass()} / {@code fk.constantName()} (legible generated code, six emit
     * sites), while correlation and split-rows readers read {@code slots} for the key tuples.
     * Classification and diagnostics additionally read {@code fk.sqlName()}. Non-null by the
     * type system: catalog misses route through
     * {@code BuildContext.synthesizeFkJoin}'s {@code FkJoinResolution} sub-taxonomy rather than
     * producing a pair list with no provenance.
     *
     * <p>{@code slots} carries the pairing as {@link JoinSlot.FkSlot}s oriented at synthesis
     * time: each slot's {@link JoinSlot#sourceSide()} is the column on the hop's origin table,
     * {@link JoinSlot#targetSide()} the column on the hop's target. The FK-direction question is
     * answered once in {@code BuildContext.synthesizeFkJoin} and baked into the pair, so readers
     * are direction-blind. The list is empty when the jOOQ catalog is unavailable (unit tests).
     */
    record ColumnPairs(ForeignKeyRef fk, List<JoinSlot.FkSlot> slots) implements On, HasSlots {
        public ColumnPairs {
            if (fk == null) {
                throw new NullPointerException(
                    "On.ColumnPairs.fk must not be null; resolution failures route through "
                    + "BuildContext.synthesizeFkJoin's FkJoinResolution sub-taxonomy.");
            }
            slots = List.copyOf(slots);
        }

        @Override public int slotCount() { return slots.size(); }
    }

    /**
     * The step joins on a user-supplied condition method (no FK involved): the generator emits
     * {@code .join(target).on(condition(sourceAlias, targetAlias))}. The
     * {@link JoinConditionRef} wrapper types the two-argument calling convention.
     */
    record Predicate(JoinConditionRef condition) implements On {
        public Predicate {
            if (condition == null) {
                throw new NullPointerException("On.Predicate.condition must not be null");
            }
        }
    }
}
