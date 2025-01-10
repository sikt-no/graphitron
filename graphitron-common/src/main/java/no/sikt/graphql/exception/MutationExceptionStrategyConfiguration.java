package no.sikt.graphql.exception;

import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defines the structure for mutation exception strategy configurations.
 * Implementations of this interface should provide mappings of supported exception types to mutations
 * and mappings of mutation names to payload creators.
 */
public interface MutationExceptionStrategyConfiguration {

    /**
     * Gets a map where the key is the exception type to be handled,
     * and the value is a list of mutation names that should be error-handled for this exception.
     *
     * @return a map of exception types to mutation names
     */
    Map<Class<? extends Throwable>, Set<String>> getMutationsForException();

    /**
     * Gets a map where the key is the mutation name,
     * and the value is a PayloadCreator that creates the payload for the mutation.
     *
     * @return a map of mutation names to payload creators
     */
    Map<String, PayloadCreator> getPayloadForMutation();

    @FunctionalInterface
    interface PayloadCreator {
        Serializable createPayload(List<?> errors);
    }
}
