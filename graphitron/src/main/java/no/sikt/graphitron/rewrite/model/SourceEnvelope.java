package no.sikt.graphitron.rewrite.model;

/**
 * How a carrier-payload data field reaches its source value(s) inside
 * {@code env.getSource()}: directly, or wrapped in the error-channel {@code Outcome}. The value
 * shape is unaffected; this axis records only the outer envelope the upstream producer wrapped
 * the value(s) in, so the data-field fetcher knows whether to read {@code env.getSource()}
 * verbatim or narrow {@code Outcome.Success} first (resolving {@code null} on the
 * {@code ErrorList} arm).
 *
 * <p>Minted once per leaf at classification time from
 * {@code FieldBuilder.carrierPayloadHasErrorsField} — the same structural signal (an
 * errors-shaped field on the payload SDL) that gives the producer its
 * {@link ErrorChannel.Mapped} channel, so the consumer-side envelope and the producer-side
 * channel agree by construction. Carried first-class on the two envelope-forking leaves,
 * {@link ChildField.SingleRecordIdField#envelope()} and
 * {@link ChildField.RecordCompositeField#envelope()}. (Formerly
 * {@code SourceKey.Reader.SourceEnvelope}, riding on the retired {@code ResultRowWalk} reader
 * arm; the batched re-fetch path never carries it — there the generator derives the same fork
 * at the type level as {@code sourceIsOutcome}.)
 */
public enum SourceEnvelope {
    /** {@code env.getSource()} is the value(s) directly (DML mutation carrier). */
    DIRECT,
    /**
     * {@code env.getSource()} is the non-null {@code Outcome} of an error-channel
 * {@code @service} carrier: the value(s) live in
     * {@code Outcome.Success.value()}, and the {@code Outcome.ErrorList} arm resolves the
     * data field to {@code null}.
     */
    OUTCOME_SUCCESS
}
