package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * The resolved parent→participant single-hop foreign-key correlation for one participant of a
 * multi-table interface/union child field (R452).
 *
 * <p>Carries the ordered {@link JoinSlot.FkSlot} column pairs of the one auto-discovered FK from
 * the parent/hub table to the participant's table: each slot's {@link JoinSlot#sourceSide()} is
 * the parent-side column and {@link JoinSlot#targetSide()} the participant-side column (oriented
 * at synthesis time in {@code BuildContext.synthesizeFkJoin}, so readers are direction-blind).
 *
 * <p>This is a type lift over the former raw {@code List<JoinStep>} carrier: the classifier
 * ({@code FieldBuilder.resolveChildPolymorphicJoinPaths}) decides "supported shape" exactly once
 * (auto-discovered single-hop FK, at least one slot) and only a conforming shape can be
 * constructed. The multi-table polymorphic emitter consumes {@link #slots()} directly and cannot
 * represent an unsupported shape (no {@code instanceof}, no blind cast, no silent
 * {@code null}-for-unsupported arm). The non-empty invariant is enforced here at construction.
 *
 * <p>Condition joins, multi-hop key chains, multi-FK disambiguation, and same-table self-FK
 * participants are the shapes this carrier deliberately cannot hold; the classifier rejects them
 * (structural for a field-level {@code @reference}, deferred for a same-table participant) rather
 * than lowering them to arbitrary-row data. Serving them is a deferred capability
 * (see {@code roadmap/per-participant-multitable-child-join-paths.md}).
 */
public record ParticipantFkPath(List<JoinSlot.FkSlot> slots) {
    public ParticipantFkPath {
        if (slots == null || slots.isEmpty()) {
            throw new IllegalArgumentException(
                "ParticipantFkPath.slots must be non-empty; the classifier resolves exactly one "
                + "single-hop FK per participant and rejects shapes that produce no correlation.");
        }
        slots = List.copyOf(slots);
    }
}
