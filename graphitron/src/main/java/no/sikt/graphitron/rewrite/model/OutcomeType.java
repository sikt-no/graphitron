package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Classification for an object type that forks at request time: a record-backed type that
 * additionally carries a single {@link ChildField.ErrorsField}. This is the named model
 * concept for "outcome type" in the error-channel vocabulary; its <em>success projection</em> is
 * its non-errors (data) fields, its <em>error projection</em> is the single errors field.
 *
 * <p>{@code OutcomeType} is a concrete carrier, not a bare predicate, so the guarantees the
 * {@code ErrorChannelWalker} relies on are structural rather than prose. The classifier is the
 * single producer: it runs {@code BuildContext.detectErrorsFieldShape}, enforces the
 * single-errors-field invariant ({@link ErrorChannelWalkerError.MultipleErrorsFields}) and the
 * nullable-success-projection invariant
 * ({@link ErrorChannelWalkerError.NonNullableSuccessProjectionField}), and only then constructs
 * {@code OutcomeType}. Possessing an {@code OutcomeType} is therefore the proof those invariants
 * hold; the walker reads {@link #errorsField()} directly rather than re-scanning.
 *
 * <p>An outcome type is structurally a record-backed object type (today's
 * {@link GraphitronType.ResultType} family) that additionally carries an errors field, so
 * {@code OutcomeType} is a classification <em>within</em> that family, not a rename of it
 * ({@code ResultType} stays the record-backed family name).
 */
public record OutcomeType(
    GraphitronType.ResultType backing,
    ChildField.ErrorsField errorsField,
    List<ChildField> successProjection
) {
    public OutcomeType {
        successProjection = List.copyOf(successProjection);
        if (backing == null) throw new IllegalArgumentException("OutcomeType: backing must be non-null");
        if (errorsField == null) throw new IllegalArgumentException("OutcomeType: errorsField must be non-null");
    }
}
