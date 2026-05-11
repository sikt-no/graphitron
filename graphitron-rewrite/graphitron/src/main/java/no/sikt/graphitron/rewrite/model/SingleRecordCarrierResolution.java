package no.sikt.graphitron.rewrite.model;

/**
 * Outcome of trying to admit an SDL Object type as a single-record DML carrier — a plain
 * SDL Object (no {@code @table}, no authored {@code @record(className)}) wrapping a single
 * {@code @table}-element data field. R75 Phase 1: the mutation classifies as
 * {@link MutationField.MutationDmlRecordField} returning {@code Result<RecordN<...>>}; the
 * data field classifies as {@link ChildField.SingleRecordTableField} and runs the response
 * SELECT outside the DML transaction.
 *
 * <p>{@link Ok} carries the resolved {@link SingleRecordCarrierShape}: the data field's
 * name, element type, table, and SDL wrapper. {@link NotCandidate} means the type is not a
 * single-record-carrier-shaped Object (e.g. it carries {@code @table}, {@code @record} with
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
public sealed interface SingleRecordCarrierResolution {

    /** Type is a single-record DML carrier; carries the resolved data-field shape. */
    record Ok(SingleRecordCarrierShape shape) implements SingleRecordCarrierResolution {}

    /** Type is not a single-record-carrier-shaped Object; consumers fall through to existing dispatch. */
    record NotCandidate() implements SingleRecordCarrierResolution {}

    /** Type is a candidate but failed a positive criterion; reason is plumbed to the validator. */
    record Rejected(String reason) implements SingleRecordCarrierResolution {}
}
