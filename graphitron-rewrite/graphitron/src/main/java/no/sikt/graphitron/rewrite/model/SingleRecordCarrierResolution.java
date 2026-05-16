package no.sikt.graphitron.rewrite.model;

/**
 * Outcome of trying to admit an SDL Object type as a single-record DML carrier — a plain
 * SDL Object (no {@code @table}) wrapping a single {@code @table}-element data field, optionally
 * carrying an {@code @record(record:{className:})} directive that names a developer-supplied
 * Java class. R75 Phase 1: the mutation classifies as
 * {@link MutationField.MutationDmlRecordField} returning {@code Result<RecordN<...>>}; the
 * data field classifies as {@link ChildField.SingleRecordTableField} and runs the response
 * SELECT outside the DML transaction.
 *
 * <p>{@link Ok} carries the resolved {@link SingleRecordCarrierShape}: the data field's
 * name, element type, table, and SDL wrapper. The {@link Ok.NoBacking} / {@link Ok.ClassBacked}
 * sub-arms split on whether the SDL type carries a developer-supplied Java class; see
 * {@link Ok} for the dispatch contract.
 *
 * <p>{@link NotCandidate} means the type is not a single-record-carrier-shaped Object (e.g. it
 * carries {@code @table}, is an interface / union / enum / scalar); consumers fall through to
 * existing dispatch without surfacing a rejection. {@link Rejected} means the type <em>is</em>
 * a candidate (plain SDL Object or {@code @record}-declared) but fails a per-condition check;
 * the reason names the failed positive criterion in the validator-mirrors-classifier shape
 * and is plumbed into the validator's rejection-message paths.
 *
 * <p>The split between {@link NotCandidate} and {@link Rejected} is the "Builder-step
 * results are sealed" principle: silent non-applicability and explicit rejection are
 * different events with different consumer reactions, so they get different sealed arms
 * rather than a single boolean-or-message return.
 */
public sealed interface SingleRecordCarrierResolution {

    /**
     * Type admits as a single-record carrier; carries the resolved data-field shape. The split
     * into {@link NoBacking} and {@link ClassBacked} lifts the carrier-class-category fork
     * into the model so consumers dispatch on the resolution outcome rather than re-classifying
     * the parent {@link GraphitronType} arm.
     *
     * <p>Two consumer sites act on the sub-arm; the rest read uniformly via {@link #shape()}:
     * <ul>
     *   <li>{@link no.sikt.graphitron.rewrite.GraphitronSchemaBuilder}'s type-level walk
     *       short-circuits per-field classification only for {@link NoBacking} (no developer
     *       class for the R88 per-field accessor-resolution pass to inspect). {@link ClassBacked}
     *       carriers fall through to normal per-type classification so R88 diagnostics surface
     *       on developer-supplied classes; the mutation classifier's producer-site helpers
     *       (e.g. {@code registerDmlCarrierDataField}) reclassify the data field via
     *       compare-then-write at mutation time when the carrier is used as a DML or
     *       {@code @service} return.</li>
     *   <li>R161's widening: the consumer-side dispatch lives in the resolution sub-arm, not in
     *       a {@code parentType instanceof PojoResultType.NoBacking} check at the call site.
     *       If a future fork emerges (e.g. distinguishing {@code JavaRecordType} from
     *       {@code Backed} POJO), the natural place to encode it is a new sub-arm of {@link Ok}
     *       rather than re-introducing consumer-side re-classification.</li>
     * </ul>
     */
    sealed interface Ok extends SingleRecordCarrierResolution {

        /** The resolved carrier shape; both sub-arms expose it uniformly. */
        SingleRecordCarrierShape shape();

        /**
         * Carrier has no developer-supplied backing class. Covers {@link GraphitronType.PlainObjectType}
         * (pre-promotion) and {@link GraphitronType.PojoResultType.NoBacking} (post-promotion).
         * Type-level classification short-circuits to carrier-walk registration; there is no
         * developer class for the per-field accessor-resolution pass (R88) to inspect.
         */
        record NoBacking(SingleRecordCarrierShape shape) implements Ok {}

        /**
         * Carrier has a developer-supplied backing class. Covers {@link GraphitronType.PojoResultType.Backed},
         * {@link GraphitronType.JavaRecordType}, {@link GraphitronType.JooqRecordType}, and
         * {@link GraphitronType.JooqTableRecordType}. R88's per-field accessor-resolution semantics
         * still apply at type-level; the mutation classifier reclassifies the data field via
         * compare-then-write at mutation time when the carrier is used as a DML or
         * {@code @service} return.
         */
        record ClassBacked(SingleRecordCarrierShape shape) implements Ok {}
    }

    /** Type is not a single-record-carrier-shaped Object; consumers fall through to existing dispatch. */
    record NotCandidate() implements SingleRecordCarrierResolution {}

    /** Type is a candidate but failed a positive criterion; reason is plumbed to the validator. */
    record Rejected(String reason) implements SingleRecordCarrierResolution {}
}
