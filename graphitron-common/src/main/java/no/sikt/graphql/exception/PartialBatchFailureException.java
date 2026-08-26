package no.sikt.graphql.exception;

import java.util.List;
import java.util.Objects;

/**
 * Thrown when part of a batch operation succeeded and part of it did not.
 * <p>
 * This exception carries the payload that was already built from the work that succeeded, so
 * {@link SchemaBasedErrorStrategy} can put the failures on that same payload rather than replacing it with an
 * empty one. It is caught inside the execution strategy and never reaches a client, unless one of the failures
 * has no {@code @error} mapping to be reported through, in which case the operation falls back to failing
 * whole, exactly as it did before per-item reporting existed.
 */
public class PartialBatchFailureException extends RuntimeException {
    private final transient Object payload;
    private final transient List<BatchItemFailure> failures;

    /**
     * @param payload  The payload built from the elements that succeeded.
     * @param failures The elements that failed. Must not be empty.
     */
    public PartialBatchFailureException(Object payload, List<BatchItemFailure> failures) {
        super(
                Objects.requireNonNull(failures, "failures == null").size() + " element(s) of this batch failed.",
                failures.isEmpty() ? null : failures.get(0).cause()
        );
        if (failures.isEmpty()) {
            throw new IllegalArgumentException("A partial batch failure must carry at least one failure.");
        }
        this.payload = payload;
        this.failures = List.copyOf(failures);
    }

    /**
     * @return The payload built from the elements that succeeded, which the failures should be reported on.
     */
    public Object getPayload() {
        return payload;
    }

    /**
     * @return The elements that failed, each with its path into the operation's input.
     */
    public List<BatchItemFailure> getFailures() {
        return failures;
    }
}
