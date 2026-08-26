package no.sikt.graphql.exception;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defines the structure for exception strategy configurations.
 * Implementations of this interface should provide mappings of supported exception types to operations
 * and mappings of field names to payload creators.
 */
public interface ExceptionStrategyConfiguration {
    /**
     * Gets a map where the key is the exception type to be handled,
     * and the value is a list of mutation names that should be error-handled for this exception.
     *
     * @return a map of exception types to mutation names
     */
    Map<Class<? extends Throwable>, Set<String>> getFieldsForException();

    /**
     * Gets a map where the key is the mutation name,
     * and the value is a PayloadCreator that creates the payload for the mutation.
     *
     * @return a map of mutation names to payload creators
     */
    Map<String, PayloadCreator> getPayloadForField();

    /**
     * Puts errors onto a payload for one operation.
     * <p>
     * The single operation is {@link #attachErrors}, which takes the payload the errors belong on. Passing
     * {@code null} for that payload asks for a fresh, otherwise empty one, which is what
     * {@link #createPayload} does and what an operation that failed outright wants. Passing a payload that
     * already carries data is what lets an operation report that some of its work succeeded and some did not.
     */
    @FunctionalInterface
    interface PayloadCreator {
        /**
         * @param payload The payload to put the errors on, or {@code null} to build an empty one first.
         * @param errors  The errors to set on the payload's errors field.
         * @return The payload, with the errors set on it.
         */
        Object attachErrors(Object payload, List<?> errors);

        /**
         * @param errors The errors to set on a new payload.
         * @return A payload carrying only these errors and no data.
         */
        default Object createPayload(List<?> errors) {
            return attachErrors(null, errors);
        }
    }
}
