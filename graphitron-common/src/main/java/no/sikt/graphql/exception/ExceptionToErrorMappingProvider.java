package no.sikt.graphql.exception;

import java.util.List;
import java.util.Map;

public interface ExceptionToErrorMappingProvider {
    /**
     * Returns DataAccessException mappings for operations.
     *
     * @return Map with operation field names as keys and corresponding exception-to-error mappings as values.
     */
    Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForOperation();

    /**
     * Returns generic exception mappings for operations.
     *
     * @return Map with operation field names as keys and corresponding exception-to-error mappings as values.
     */
    Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForOperation();
}
