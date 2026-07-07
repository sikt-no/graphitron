package no.sikt.graphitron.rewrite.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability for the slot-carrying types: a list of {@link JoinSlot}s pairing source / target
 * columns for correlation predicates. Implemented by {@link On.ColumnPairs} (FK-derived pairs on
 * a {@link JoinStep.Hop}) and {@link JoinStep.LiftedHop} (lifter identity pairs) — exactly the
 * two slot populations that coexist in the R438-to-R431 window. The capability expresses what is
 * uniformly true of both (slot iteration); which population a reader holds stays a sealed
 * dispatch on the carrier. Dies with {@code LiftedHop} in R431, when {@code ColumnPairs} becomes
 * its only implementor.
 *
 * <p>{@link #slots()} returns {@link Iterable} rather than {@link List} on purpose: positional
 * methods ({@code .get(i)}, {@code .getFirst()}, {@code .subList(...)}) become compile errors at
 * the consumer rather than grep findings. Cardinality stays available through
 * {@link #slotCount()}. Implementors may store a {@code List} component; iteration through the
 * capability is the read contract. The variant identity (FK pairing vs lifter identity) lives on
 * the concrete {@link JoinSlot} permit returned by iteration; consumers iterate uniformly through
 * {@link JoinSlot#sourceSide()} and {@link JoinSlot#targetSide()} regardless of permit.
 */
public interface HasSlots {
    Iterable<? extends JoinSlot> slots();
    int slotCount();

    /**
     * Source-side columns, materialised as a {@link List} for readers that need the columns
     * themselves (e.g. constructing a {@link SourceKey} entry-point column tuple) rather than
     * slot-by-slot iteration. The order matches {@link #slots()}; index {@code i} is
     * {@code slots[i].sourceSide()}.
     */
    default List<ColumnRef> sourceSideColumns() {
        List<ColumnRef> out = new ArrayList<>(slotCount());
        for (JoinSlot slot : slots()) out.add(slot.sourceSide());
        return List.copyOf(out);
    }

    /**
     * Target-side columns, materialised as a {@link List}. The order matches {@link #slots()};
     * index {@code i} is {@code slots[i].targetSide()}.
     */
    default List<ColumnRef> targetSideColumns() {
        List<ColumnRef> out = new ArrayList<>(slotCount());
        for (JoinSlot slot : slots()) out.add(slot.targetSide());
        return List.copyOf(out);
    }
}
