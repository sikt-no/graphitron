package no.sikt.graphitron.rewrite.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Sealed sub-family of {@link Rejection.AuthorError} for the error-channel domain:
 * the {@code ErrorChannelWalker} that resolves an outcome type's errors-field channel onto
 * {@link ErrorChannel.Mapped}, plus the {@code OutcomeType} classification that produces the
 * walker's input. Each typed arm carries the structural data its diagnostic message and LSP
 * {@code relatedInformation} need; downstream tooling switches on the arm rather than parsing
 * prose.
 *
 * <p>The arm-to-code mapping is exposed via {@link #lspCode()} so the orchestrator can project a
 * typed error to a {@link Diagnostic} under the {@code graphitron.error-channel.} namespace
 * without a separate dispatch table. The stable wire strings are written next to each arm rather
 * than derived from the Java identifier, mirroring the {@link ServiceMethodCallError} wire
 * convention.
 *
 * <p>Two arms ({@link MultipleErrorsFields}, {@link NonNullableSuccessProjectionField}) are
 * raised by the {@code OutcomeType} classification that produces the walker's input; the rest are
 * raised by {@code walk()}. They share one family because they share one SDL surface (the outcome
 * type and its errors field) and one LSP namespace; each arm's javadoc names its actual raiser.
 * Keeping {@code OutcomeType} construction as the single producer of the two structural arms is a
 * smaller seam than pushing errors-field detection into the walker that
 * {@code BuildContext.detectErrorsFieldShape} already centralises.
 *
 * <p>Following the {@link ServiceMethodCallError} precedent, this is its own sibling sub-seal of {@link Rejection.AuthorError}
 * rather than a set of arms under the flat {@link Rejection.AuthorError.Structural}, keeping
 * {@code AuthorError}'s permits one-row-per-walker as the dimensional pivot scales.
 */
public sealed interface ErrorChannelWalkerError extends Rejection.AuthorError permits
    ErrorChannelWalkerError.MultipleErrorsFields,
    ErrorChannelWalkerError.NonNullableSuccessProjectionField,
    ErrorChannelWalkerError.NonNullableErrorsField,
    ErrorChannelWalkerError.ChannelRuleViolation,
    ErrorChannelWalkerError.HandlerSourceAccessorMissing
{
    /** LSP wire code under the {@code graphitron.error-channel.} namespace. */
    String lspCode();

    @Override default Rejection prefixedWith(String prefix) {
        // Typed arms keep their structural components; prefixing is a no-op concerning structure.
        // The orchestrator's renderer prepends author-facing prose via diagnostic projection, not
        // via Rejection#prefixedWith.
        return this;
    }

    /**
     * Raised by the {@code OutcomeType} classification: a type carries more than one errors field.
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
     * Raised by the {@code OutcomeType} classification: a success-projection (data) field is
     * non-null, which would bubble null up and drop the errors field on the error arm.
     * Success-projection fields must be nullable.
     */
    record NonNullableSuccessProjectionField(
        String outcomeTypeName,
        String fieldName
    ) implements ErrorChannelWalkerError {
        @Override public String message() {
            return "outcome type '" + outcomeTypeName + "' has a non-null success-projection field '"
                + fieldName + "'; on the error arm this field resolves null and would raise "
                + "NonNullableFieldWasNullError, dropping the sibling errors field. "
                + "Success-projection fields must be nullable";
        }
        @Override public String lspCode() { return "graphitron.error-channel.non-nullable-success-field"; }
    }

    /**
 * Raised by the {@code OutcomeType} classification: the errors field carries a non-null
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

    /**
     * Raised by {@code walk()}: a channel-level handler rule was violated. Rule 7 (no two
     * VALIDATION handlers in one channel) and rule 8 (no duplicate match-criteria across the
     * flattened handler list) are today's rules; future rules slot in via {@code ruleNumber}.
     */
    record ChannelRuleViolation(
        String payloadTypeName,
        String errorsFieldName,
        int ruleNumber,
        String detail
    ) implements ErrorChannelWalkerError {
        @Override public String message() {
            return "outcome type '" + payloadTypeName + "' errors field '" + errorsFieldName
                + "' violates channel rule " + ruleNumber + ": " + detail;
        }
        @Override public String lspCode() {
            return switch (ruleNumber) {
                case 7 -> "graphitron.error-channel.multi-validation";
                case 8 -> "graphitron.error-channel.duplicate-match-criteria";
                default -> "graphitron.error-channel.channel-rule-violation";
            };
        }
    }

    /**
     * Raised by {@code walk()}: an {@code @error} type's handler source class does not expose a
     * {@code PropertyDataFetcher}-visible accessor for one of the {@code @error} type's declared
     * SDL fields ({@code path} and {@code message} are exempt). Carries the handler details so the
     * diagnostic can list what was available. {@code accessorBaseName} is the accessor the resolver
     * looked for: equal to {@code missingFieldName} when no {@code @field(name:)} override applies,
     * otherwise the directive value (the {@code message()} then flags the remap).
     */
    record HandlerSourceAccessorMissing(
        String payloadTypeName,
        String errorTypeName,
        String handlerClassName,
        String missingFieldName,
        String accessorBaseName,
        List<String> available
    ) implements ErrorChannelWalkerError {
        public HandlerSourceAccessorMissing { available = List.copyOf(available); }
        @Override public String message() {
            var sb = new StringBuilder("outcome type '").append(payloadTypeName)
                .append("' @error type '").append(errorTypeName)
                .append("': handler source class '").append(handlerClassName)
                .append("' exposes no accessor for SDL field '").append(missingFieldName).append('\'');
            if (!accessorBaseName.equals(missingFieldName)) {
                sb.append(" (remapped to '").append(accessorBaseName).append("' by @field)");
            }
            if (!available.isEmpty()) {
                sb.append("; available accessors: ")
                  .append(available.stream().collect(Collectors.joining(", ")));
            }
            return sb.toString();
        }
        @Override public String lspCode() { return "graphitron.error-channel.handler-accessor-missing"; }
    }
}
