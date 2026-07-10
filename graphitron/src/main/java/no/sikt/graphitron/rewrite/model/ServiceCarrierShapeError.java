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
 * {@link SourceKey.Cardinality} values and the offending field/payload coordinate, not a reason string
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
     * {@link SourceKey.Cardinality#MANY}) whose {@code @service} producer returns a single value
     * (arrival {@link SourceKey.Cardinality#ONE}). graphql-java iterates the producer's return into
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
        SourceKey.Cardinality carrierArrival,
        SourceKey.Cardinality producerArrival,
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
     * {@link SourceKey.Cardinality#MANY}) whose {@code @table}-element data field is itself a list
     * (arrival {@link SourceKey.Cardinality#MANY}), produced by a flat {@code List<Record>}. The
     * producer's flat list is consumed element-by-element to build the {@code [Payload]} carrier, so a
     * single produced record reaches each {@code Payload} and cannot also populate a list-valued data
     * field: the emitter's per-element key extraction casts that single record to {@code Iterable<?>}
     * and {@code ClassCastException}s on every request (a defensive runtime cast failing after a green
     * build, the acceptance axiom's forbidden shape). Beneath the crash sits a semantic hole — one flat
     * producer list cannot say which records belong to which payload element. Distinct from
     * {@link ProducerArrivalMismatch} because it is a different axis pairing (data-field arrival vs. a
     * flat producer list that cannot re-nest per carrier element), so it earns its own arm.
     */
    record DataFieldArrivalConflict(
        String payloadTypeName,
        String carrierParentType,
        String carrierFieldName,
        String dataFieldName,
        String dataFieldElementType,
        SourceKey.Cardinality carrierArrival,
        SourceKey.Cardinality dataFieldArrival
    ) implements ServiceCarrierShapeError {

        @Override public String message() {
            return "@service carrier field '" + carrierParentType + "." + carrierFieldName
                + "' returns a list payload '[" + payloadTypeName + "]' (arrival " + carrierArrival
                + ") whose data field '" + payloadTypeName + "." + dataFieldName
                + "' is itself a list of '" + dataFieldElementType + "' (arrival " + dataFieldArrival
                + "); the producer's flat list is consumed element-by-element to build the '["
                + payloadTypeName + "]' carrier, so a single produced record reaches each '"
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
