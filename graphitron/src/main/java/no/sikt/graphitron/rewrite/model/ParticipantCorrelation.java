package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.Objects;

/**
 * The resolved parent→participant correlation for one participant of a multi-table interface/union
 * child field, decided once at classification (generalizing the earlier single-arm
 * {@code ParticipantFkPath}).
 *
 * <p>Two arms, distinguished by whether the branch joins any real tables:
 *
 * <ul>
 *   <li>{@link KeyTupleWhere} — the branch joins nothing; correlation is a key-tuple WHERE against
 *       the parent's bound key values (single-fetch form) or the {@code VALUES} join predicate
 *       (batched forms). Auto-discovered single-FK routes, multi-FK-disambiguated
 *       {@code @referenceFor} routes, and same-table self-FK {@code @referenceFor} routes all lower
 *       here: every one is a single-hop foreign key from the participant's table to the parent's,
 *       so the parent side is always bound values rather than a joined alias. This is the
 *       former {@code ParticipantFkPath} carrier arm, renamed and re-payloaded onto
 *       {@link On.ColumnPairs}.</li>
 *   <li>{@link JoinedCorrelation} — the branch joins real tables. Each hop's {@link On} already
 *       distinguishes an FK bridge ({@link On.ColumnPairs}) from an authored predicate
 *       ({@link On.Predicate}); multi-hop is list length greater than one, condition correlation is
 *       a hop carrying {@link On.Predicate}. Non-empty enforced at construction.</li>
 * </ul>
 *
 * <p>The classifier ({@code FieldBuilder.resolveChildPolymorphicJoinPaths}) decides the supported
 * shape exactly once and only a conforming arm can be constructed; the multi-table polymorphic
 * emitter dispatches exhaustively on this seal and cannot represent an unsupported shape.
 */
public sealed interface ParticipantCorrelation
        permits ParticipantCorrelation.KeyTupleWhere, ParticipantCorrelation.JoinedCorrelation {

    /**
     * The branch joins nothing: correlation is a single-hop foreign-key key-tuple compared against
     * the parent's bound key values. {@code on} carries the resolved FK column pairs oriented at
     * synthesis time (each slot's {@link JoinSlot#sourceSide()} the parent-side column,
     * {@link JoinSlot#targetSide()} the participant-side column), so emitters iterate the slots
     * direction-blind. Non-empty slots enforced at construction: the classifier resolves exactly one
     * single-hop FK per participant and rejects shapes that produce no correlation.
     */
    record KeyTupleWhere(On.ColumnPairs on) implements ParticipantCorrelation {
        public KeyTupleWhere {
            Objects.requireNonNull(on, "KeyTupleWhere.on");
            if (on.slots().isEmpty()) {
                throw new IllegalArgumentException(
                    "KeyTupleWhere.on.slots must be non-empty; the classifier resolves exactly one "
                    + "single-hop FK per participant and rejects shapes that produce no correlation.");
            }
        }

        /** The resolved FK column pairs; convenience accessor over {@link On.ColumnPairs#slots()}. */
        public List<JoinSlot.FkSlot> slots() {
            return on.slots();
        }
    }

    /**
     * The branch joins real tables from the participant's table back toward the parent. {@code hops}
     * is the ordered join path; non-empty by construction. Emitted by slice 2 (all-FK hops) and
     * slice 3 (a hop carrying {@link On.Predicate}).
     */
    record JoinedCorrelation(List<JoinStep> hops) implements ParticipantCorrelation {
        public JoinedCorrelation {
            if (hops == null || hops.isEmpty()) {
                throw new IllegalArgumentException(
                    "JoinedCorrelation.hops must be non-empty; a branch that joins nothing is a "
                    + "KeyTupleWhere.");
            }
            hops = List.copyOf(hops);
        }
    }
}
