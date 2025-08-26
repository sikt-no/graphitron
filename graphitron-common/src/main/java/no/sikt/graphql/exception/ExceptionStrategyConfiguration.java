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

    @FunctionalInterface
    interface PayloadCreator {
        Object createPayload(List<?> errors);
    }
}
