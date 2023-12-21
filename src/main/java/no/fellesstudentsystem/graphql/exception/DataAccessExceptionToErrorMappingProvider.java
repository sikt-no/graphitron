package no.fellesstudentsystem.graphql.exception;

import java.util.List;
import java.util.Map;

public interface DataAccessExceptionToErrorMappingProvider {

    /**
     * Returns mappings for mutations.
     *
     * @return Map with mutation names as keys and corresponding exception-to-error mappings as values.
     */
    Map<String, List<DataAccessExceptionContentToErrorMapping>> getMappingsForMutation();

}
