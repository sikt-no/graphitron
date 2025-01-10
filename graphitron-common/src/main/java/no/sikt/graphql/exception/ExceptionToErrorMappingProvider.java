package no.sikt.graphql.exception;

import java.util.List;
import java.util.Map;

public interface ExceptionToErrorMappingProvider {

    /**
     * Returns DataAccessException mappings for mutations.
     *
     * @return Map with mutation names as keys and corresponding exception-to-error mappings as values.
     */
    Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForMutation();

    /**
     * Returns generic exception mappings for mutations.
     *
     * @return Map with mutation names as keys and corresponding exception-to-error mappings as values.
     */
    Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForMutation();

}
