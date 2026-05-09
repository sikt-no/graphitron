package no.sikt.graphitron.rewrite.model;

/**
 * Outcome of trying to admit an SDL Object type as a passthrough payload — a wire-format
 * wrapper around a {@code @table}-element data field that the rewrite resolves to the
 * inner {@link ReturnTypeRef.TableBoundReturnType} at the boundary, with no authored
 * carrier class.
 *
 * <p>{@link Ok} carries the resolved {@link PassthroughInfo}: the data field's name,
 * element type, table, and SDL wrapper. {@link NotCandidate} means the type is not a
 * passthrough-shaped Object at all (e.g. it carries {@code @table}, {@code @record} with
 * {@code className}, or is an interface / union / enum / scalar); consumers fall through
 * to existing dispatch without surfacing a rejection. {@link Rejected} means the type
 * <em>is</em> a candidate (no domain directive, or {@code @record} without
 * {@code className}) but fails a per-condition check; the reason names the failed positive
 * criterion in the validator-mirrors-classifier shape and is plumbed into the validator's
 * rejection-message paths.
 *
 * <p>The split between {@link NotCandidate} and {@link Rejected} is the "Builder-step
 * results are sealed" principle: silent non-applicability and explicit rejection are
 * different events with different consumer reactions, so they get different sealed arms
 * rather than a single boolean-or-message return.
 */
public sealed interface PassthroughResolution {

    /** Type is a passthrough payload; carries the resolved data-field shape. */
    record Ok(PassthroughInfo info) implements PassthroughResolution {}

    /** Type is not a passthrough-shaped Object; consumers fall through to existing dispatch. */
    record NotCandidate() implements PassthroughResolution {}

    /** Type is a candidate but failed a positive criterion; reason is plumbed to the validator. */
    record Rejected(String reason) implements PassthroughResolution {}
}
