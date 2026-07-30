package no.sikt.graphitron.rewrite;

import java.util.Objects;

/**
 * A typed coverage exemption: one row of an exemption-obligation registry entry (see
 * {@code ExemptionRegistry}). The arm is the triage taxonomy as data rather than prose, so the
 * grain worklist is a filter over the live lists instead of a hand census that drifts: a new
 * exemption must pick an arm, each arm carries what its closure needs, and an arm that claims
 * an existing demonstration names the covering test as a {@code Class<?>} so a renamed or
 * deleted covering test is a compile error rather than a rotting sentence. The free-text
 * {@link #reason()} carries only the why.
 *
 * <p>The original taxonomy predicted a rides-another-rows-key category; its single member
 * turned out to be mis-triaged (the projection side had no grain defect and the residue was an
 * instrument gap), so no such arm exists: the warning it carried lives in the programme's
 * grain invariant, not in an enum constant no population confirms.
 */
public sealed interface Exemption {

    /** The specific story: why this row exists, beyond what the arm already states. */
    String reason();

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("an exemption carries its reason");
        }
        return reason;
    }

    /**
     * The model declares the arm but no schema-reachable path produces it yet: the classifier
     * rejects upstream or does not mint it. {@link #blocker} names what has to land first.
     * Leaves the list when the behaviour lands.
     */
    record Unimplemented(String blocker, String reason) implements Exemption {
        public Unimplemented {
            Objects.requireNonNull(blocker, "what has to land first is the arm's whole content");
            requireReason(reason);
        }
    }

    /**
     * Synthesised with no SDL declaration to carry a corpus annotation. This is the
     * connection-promotion residue: the entries are command outputs stored in the fact model,
     * and they leave the list when connection synthesis becomes a relation.
     */
    record SynthesisedNoSdlOrigin(String reason) implements Exemption {
        public SynthesisedNoSdlOrigin {
            requireReason(reason);
        }
    }

    /**
     * Demonstrated, but by a test shape the obligation's coverage walker does not read.
     * {@link #walker} names the instrument that would have to widen; {@link #demonstratedBy}
     * is the covering test, compile-checked. Leaves the list when the walker widens or the
     * demonstration moves into the walked shape.
     */
    record WalkerGap(String walker, Class<?> demonstratedBy, String reason) implements Exemption {
        public WalkerGap {
            Objects.requireNonNull(walker, "the instrument that would have to widen");
            Objects.requireNonNull(demonstratedBy, "a walker gap claims an existing demonstration");
            requireReason(reason);
        }
    }

    /**
     * The fixture catalog genuinely lacks the shape ({@link #missing} names it); no
     * demonstration exists anywhere yet at the obligation's grain. Leaves the list when the
     * fixture is authored.
     */
    record FixtureAbsent(String missing, String reason) implements Exemption {
        public FixtureAbsent {
            Objects.requireNonNull(missing, "what fixture would close the row is the arm's whole content");
            requireReason(reason);
        }
    }

    /**
     * Demonstrated, but only under a catalog the corpus harness cannot reach (the harness
     * hard-wires one {@code RewriteContext}); {@link #demonstratedBy} is the covering test on
     * the other catalog, compile-checked. Leaves the list when the harness gains a per-example
     * context slot or the shape lands in the corpus's own catalog.
     */
    record HarnessSingleCatalog(Class<?> demonstratedBy, String reason) implements Exemption {
        public HarnessSingleCatalog {
            Objects.requireNonNull(demonstratedBy, "a harness gap claims an existing demonstration");
            requireReason(reason);
        }
    }
}
