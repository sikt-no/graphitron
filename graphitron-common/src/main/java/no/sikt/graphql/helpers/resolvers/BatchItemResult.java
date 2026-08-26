package no.sikt.graphql.helpers.resolvers;

import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * The outcome of one element of a batch, as reported by a {@code @service} method.
 * <p>
 * A service that returns {@code List<BatchItemResult<T>>} instead of {@code List<T>} is telling Graphitron that
 * elements of the batch can fail on their own. Graphitron builds the payload's data from the elements that
 * succeeded, and reports the ones that did not through the payload's {@code errors} field.
 * <p>
 * The returned list must have one entry per input element, in input order. That is how Graphitron works out
 * which element a failure belongs to, and it is checked at runtime.
 *
 * @param <T> The type the service would have returned for a single element had nothing failed.
 */
public final class BatchItemResult<T> {
    private final T value;
    private final Throwable cause;

    private BatchItemResult(T value, Throwable cause) {
        this.value = value;
        this.cause = cause;
    }

    /**
     * @param value The result for this element. May be null if the service has nothing to return for it.
     * @return A result marking this element as succeeded.
     */
    public static <T> BatchItemResult<T> success(T value) {
        return new BatchItemResult<>(value, null);
    }

    /**
     * @param cause Why this element failed. Reported through the payload's errors field if the schema has an
     *              error type that this exception maps to, and otherwise fails the whole operation.
     * @return A result marking this element as failed.
     */
    public static <T> BatchItemResult<T> failure(Throwable cause) {
        return new BatchItemResult<>(null, Objects.requireNonNull(cause, "cause == null"));
    }

    /**
     * @return Whether this element succeeded.
     */
    public boolean isSuccess() {
        return cause == null;
    }

    /**
     * @return The result for this element.
     * @throws NoSuchElementException if this element failed.
     */
    public T value() {
        if (!isSuccess()) {
            throw new NoSuchElementException("This batch element failed, so it has no value.");
        }
        return value;
    }

    /**
     * @return Why this element failed.
     * @throws NoSuchElementException if this element succeeded.
     */
    public Throwable cause() {
        if (isSuccess()) {
            throw new NoSuchElementException("This batch element succeeded, so it has no cause.");
        }
        return cause;
    }
}
