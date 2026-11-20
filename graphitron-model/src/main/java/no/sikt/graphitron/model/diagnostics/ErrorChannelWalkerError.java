package no.sikt.graphitron.model.diagnostics;

import java.util.List;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the error-channel domain: errors raised
 * while an outcome type's errors-field channel resolves onto {@code ErrorChannel.Mapped}, and by
 * the outcome-type classification that precedes it. Each typed arm carries the structural data its diagnostic message and LSP
 * {@code relatedInformation} need; downstream tooling switches on the arm rather than parsing
 * prose.
 *
 * <p>{@link #lspCode()} projects an arm to its {@code Diagnostic} code under the
 * {@code graphitron.error-channel.} namespace. The wire strings are literals next to each arm, not
 * derived from the Java identifier.
 *
 * <p>Classification-raised and channel-raised arms share one family because they share one SDL
 * surface (the outcome type and its errors field) and one LSP namespace; each arm's javadoc names
 * its raiser. The outcome-type classification stays the single producer of the structural arms
 * because {@code BuildContext.detectErrorsFieldShape} already centralises errors-field detection.
 * This is its own sibling sub-seal of {@link Rejection.AuthorError} rather than arms under the
 * flat {@link Rejection.AuthorError.Structural}, keeping {@code AuthorError}'s permits list
 * one row per walker.
 */
public sealed interface ErrorChannelWalkerError extends Rejection.AuthorError permits
    ErrorChannelWalkerError.MultipleErrorsFields,
    ErrorChannelWalkerError.NonNullableSuccessProjectionField,
    ErrorChannelWalkerError.NonNullableErrorsField
{
    /** LSP wire code under the {@code graphitron.error-channel.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // No-op: the orchestrator's renderer prepends author-facing prose via diagnostic
        // projection, not via Rejection#prefixedWith.
        return this;
    }

    /**
     * Raised by the outcome-type classification: a type carries more than one errors field.
     * The binary {@code Outcome} witness has one error slot, so a type with two errors fields has
     * no well-defined fork.
     */
    record MultipleErrorsFields(
        String outcomeTypeName,
        List<String> errorsFieldNames
    ) implements ErrorChannelWalkerError {
        public MultipleErrorsFields { errorsFieldNames = List.copyOf(errorsFieldNames); }
        @Override public String message() {
            return "outcome type '" + outcomeTypeName + "' has more than one errors field ("
                + String.join(", ", errorsFieldNames)
                + "); exactly one errors field is allowed so the success/error fork is well-defined";
        }
        @Override public String lspCode() { return "graphitron.error-channel.multiple-errors-fields"; }
    }

    /**
     * Raised by the outcome-type classification: a success-projection (data) field has no
     * value to answer on the error arm. On that arm a nullable data field answers {@code null} and
     * a non-null plain list answers {@code []}; a non-null single value or connection has no empty
     * value, and a {@code null} would bubble up and drop the errors field. Data fields must be
     * nullable or non-null plain lists.
     */
    record NonNullableSuccessProjectionField(
        String outcomeTypeName,
        String fieldName
    ) implements ErrorChannelWalkerError {
        @Override public String message() {
            return "outcome type '" + outcomeTypeName + "' has a non-null success-projection field '"
                + fieldName + "' with no empty value; on the error arm this field would resolve null "
                + "and raise NonNullableFieldWasNullError, dropping the sibling errors field. "
                + "Success-projection fields must be nullable or a non-null plain list, which "
                + "answers [] on the error arm (a connection counts as a single value)";
        }
        @Override public String lspCode() { return "graphitron.error-channel.non-nullable-success-field"; }
    }

    /**
     * Raised by the outcome-type classification: the errors field carries a non-null
     * list type ({@code [X!]!}). The mirror of {@link NonNullableSuccessProjectionField}: the
     * success arm resolves the errors field to {@code null} (there are no errors), so a non-null
     * errors field would raise {@code NonNullableFieldWasNullError} and drop the sibling data field
     * on every success. Errors fields must be nullable so {@code null} is a legal success-arm value.
     */
    record NonNullableErrorsField(
        String outcomeTypeName,
        String fieldName
    ) implements ErrorChannelWalkerError {
        @Override public String message() {
            return "outcome type '" + outcomeTypeName + "' has a non-null errors field '"
                + fieldName + "'; on the success arm there are no errors and this field resolves "
                + "null, which would raise NonNullableFieldWasNullError and drop the sibling data "
                + "field. Errors fields must be nullable";
        }
        @Override public String lspCode() { return "graphitron.error-channel.non-nullable-errors-field"; }
    }
}
