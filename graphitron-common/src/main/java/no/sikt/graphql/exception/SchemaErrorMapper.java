package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps exceptions to schema-based error objects.
 * <p>
 * This mapper converts Java exceptions into GraphQL error objects that are defined
 * in the schema (via @error directives). These errors become part of the data response
 * in payload fields, not top-level errors.
 * <p>
 * Only exceptions that match the configured @error directives are mapped.
 * Unmatched exceptions are not handled here and will become top-level errors.
 */
public class SchemaErrorMapper {

    private final Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForOperation;
    private final Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForOperation;
    private final DataAccessExceptionMapper dataAccessExceptionMapper;

    public SchemaErrorMapper(
            Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForOperation,
            Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForOperation,
            DataAccessExceptionMapper dataAccessExceptionMapper) {
        this.dataAccessMappingsForOperation = dataAccessMappingsForOperation;
        this.genericMappingsForOperation = genericMappingsForOperation;
        this.dataAccessExceptionMapper = dataAccessExceptionMapper;
    }

    /**
     * Maps a DataAccessException to an error object for the specified operation.
     * First tries to find a matching configured mapping, otherwise returns a default error.
     */
    public Object mapDataAccessException(DataAccessException exception, String operationName, DataAccessDefaultErrorCreator defaultErrorCreator) {
        return mapDataAccessException(exception, operationName, List.of(operationName), defaultErrorCreator);
    }

    /**
     * Maps a DataAccessException to an error object, reported at the given path rather than at the operation
     * itself. Used when the failure belongs to one element of a batch instead of to the whole operation.
     */
    public Object mapDataAccessException(DataAccessException exception, String operationName, List<String> path,
                                         DataAccessDefaultErrorCreator defaultErrorCreator) {
        return Optional.ofNullable(dataAccessMappingsForOperation.get(operationName))
                .flatMap(mappings -> findMatchingDataAccessMapping(exception, path, mappings))
                .orElseGet(() -> defaultErrorCreator.createDefaultError(operationName, path, dataAccessExceptionMapper.getMsgFromException(exception)));
    }

    /**
     * Maps a generic business logic exception to an error object for the specified operation.
     * Returns an Optional containing the mapped error if a matching mapping is found.
     */
    public Optional<Object> mapBusinessLogicException(Throwable exception, String operationName) {
        return mapBusinessLogicException(exception, operationName, List.of(operationName));
    }

    /**
     * Maps a generic business logic exception to an error object, reported at the given path rather than at the
     * operation itself. Used when the failure belongs to one element of a batch instead of to the whole operation.
     */
    public Optional<Object> mapBusinessLogicException(Throwable exception, String operationName, List<String> path) {
        return Optional.ofNullable(genericMappingsForOperation.get(operationName))
                .flatMap(mappings -> findMatchingGenericMapping(exception, path, mappings));
    }

    private Optional<Object> findMatchingDataAccessMapping(DataAccessException exception, List<String> path,
                                                           List<DataAccessExceptionContentToErrorMapping> mappings) {
        return mappings.stream()
                .filter(mapping -> mapping.matches(exception))
                .findFirst()
                .map(mapping -> mapping.handleError(path, exception.getMessage()));
    }

    private Optional<Object> findMatchingGenericMapping(Throwable exception, List<String> path,
                                                        List<GenericExceptionContentToErrorMapping> mappings) {
        return mappings.stream()
                .filter(mapping -> mapping.matches(exception))
                .findFirst()
                .map(mapping -> mapping.handleError(path, exception.getMessage()));
    }

    /**
     * Functional interface for creating default DataAccess errors.
     * This is used when no specific mapping is found for a DataAccessException.
     */
    @FunctionalInterface
    public interface DataAccessDefaultErrorCreator {
        Object createDefaultError(String operationName, List<String> path, String message);
    }
}