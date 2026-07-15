package no.sikt.graphitron.rewrite.model;

/**
 * R308 — sealed sub-family of {@link Rejection.AuthorError} for the {@code @service} list-payload
 * carrier shape verdict. Sibling to {@link MutationTableArgError} / {@link ServiceMethodCallError}:
 * per the dimensional-model-pivot principle, a new concern adds its own sub-seal of
 * {@link Rejection.AuthorError} (and one row in that interface's {@code permits} clause) with its own
 * {@link #lspCode()} namespace, rather than collapsing into the flat
 * {@link Rejection.AuthorError.Structural} bare-string arm.
 *
 * <p>These arms are the typed reject variants of {@link no.sikt.graphitron.rewrite.BuildContext.ServiceCarrierShape},
 * the classify-time verdict over the triple (carrier field wrapper, {@code @service} producer return
 * shape, payload data-field wrapper). Each arm carries the <em>disagreeing axes</em> as typed
 * {@link Arity} values and the offending field/payload coordinate, not a reason string
 * composed at the detection site ({@code DmlPayloadScan.Reject(String)} is the debt this deliberately
 * does not replicate). The verdict only ever rejects a <em>list-returning</em> carrier
 * ({@code @service ...: [Payload]}); a single carrier is always coherent and is left to its existing
 * classification, so a coherent shape's model and emit are byte-for-byte unchanged.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} under the
 * {@code graphitron.service-carrier-shape.} namespace so the LSP {@code Diagnostic} projector reads
 * the stable wire code without a separate dispatch table.
 */
public sealed interface ServiceCarrierShapeError extends Rejection.AuthorError permits
    ServiceCarrierShapeError.ProducerArrivalMismatch,
    ServiceCarrierShapeError.DataFieldArrivalConflict
{
    /** LSP wire code under the {@code graphitron.service-carrier-shape.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // The typed arm keeps its structural components; prefixing is a no-op concerning structure.
        return this;
    }

    /**
     * A list-returning carrier field ({@code @service ...: [Payload]}, arrival
     * {@link Arity#MANY}) whose {@code @service} producer returns a single value
     * (arrival {@link Arity#ONE}). graphql-java iterates the producer's return into
     * the {@code [Payload]} list, so a single produced value yields a non-iterable source for a list
     * SDL type and list coercion fails at runtime. This one arm subsumes what the uncoordinated reads
     * used to surface three different ways: the a1 silent admit (a bare-record producer into a single
     * {@code @table} data field), and the two misleading rejections (a class-backed carrier, and a
     * carrier with a {@code @table} data field, each rejected with a record-handoff message that
     * steered the author toward the wrong fix). All three are one fact: carrier arrival disagrees with
     * producer arrival.
     */
    record ProducerArrivalMismatch(
        String payloadTypeName,
        String carrierParentType,
        String carrierFieldName,
        Arity carrierArrival,
        Arity producerArrival,
        String serviceClassName,
        String methodName
    ) implements ServiceCarrierShapeError {

        @Override public String message() {
            return "@service carrier field '" + carrierParentType + "." + carrierFieldName
                + "' returns a list payload '[" + payloadTypeName + "]' (arrival " + carrierArrival
                + ") but its producer '" + serviceClassName + "." + methodName
                + "' returns a single value (arrival " + producerArrival + "); a list-returning "
                + "carrier needs a collection producer (e.g. List<…>) so each returned element becomes "
                + "one '" + payloadTypeName + "'. Either return a List from the producer, or make the "
                + "carrier field single ('" + payloadTypeName + "' instead of '[" + payloadTypeName + "]').";
        }

        @Override public String lspCode() {
            return "graphitron.service-carrier-shape.producer-arrival-mismatch";
        }
    }

    /**
     * A list-returning carrier field ({@code @service ...: [Payload]}, arrival
     * {@link Arity#MANY}) whose data field is itself a list (arrival
     * {@link Arity#MANY}), produced by a flat collection. The producer's flat list is
     * consumed element-by-element to build the {@code [Payload]} carrier, so a single produced value
     * reaches each {@code Payload} and cannot also populate a list-valued data field, and
     * {@code ClassCastException}s on every request (a defensive runtime cast failing after a green build,
     * the acceptance axiom's forbidden shape). Both admitting element kinds crash this way: a
     * {@code @table}-element data field ({@code List<Record>} producer) on the emitter's per-element key
     * extraction casting the single record to {@code Iterable<?>}; a class-backed record-composite
     * ({@code RecordElement}) data field ({@code List<Composite>} producer) on the source-passthrough
     * casting one composite to {@code List<Composite>} ({@code FetcherEmitter.buildRecordCompositeFetcherValue}).
     * Beneath the crash sits a semantic hole: one flat producer list cannot say which values belong to
     * which payload element, and filling the shape would need a {@code List<List<…>>} producer the model
     * does not have. Only an ID-element data field re-nests per element and stays coherent. Distinct from
     * {@link ProducerArrivalMismatch} because it is a different axis pairing (data-field arrival vs. a
     * flat producer list that cannot re-nest per carrier element), so it earns its own arm.
     */
    record DataFieldArrivalConflict(
        String payloadTypeName,
        String carrierParentType,
        String carrierFieldName,
        String dataFieldName,
        String dataFieldElementType,
        Arity carrierArrival,
        Arity dataFieldArrival
    ) implements ServiceCarrierShapeError {

        @Override public String message() {
            return "@service carrier field '" + carrierParentType + "." + carrierFieldName
                + "' returns a list payload '[" + payloadTypeName + "]' (arrival " + carrierArrival
                + ") whose data field '" + payloadTypeName + "." + dataFieldName
                + "' is itself a list of '" + dataFieldElementType + "' (arrival " + dataFieldArrival
                + "); the producer's flat list is consumed element-by-element to build the '["
                + payloadTypeName + "]' carrier, so a single produced value reaches each '"
                + payloadTypeName + "' and cannot also populate a list-valued '" + dataFieldName
                + "'. Make the data field single ('" + dataFieldElementType + "' instead of '["
                + dataFieldElementType + "]'), or make the carrier field single ('" + payloadTypeName
                + "').";
        }

        @Override public String lspCode() {
            return "graphitron.service-carrier-shape.data-field-arrival-conflict";
        }
    }
}
