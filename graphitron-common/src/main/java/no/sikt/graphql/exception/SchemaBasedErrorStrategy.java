package no.sikt.graphql.exception;

import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import org.jooq.exception.DataAccessException;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Strategy for handling exceptions as schema-based (ad-hoc) errors.
 * <p>
 * Schema-based errors are part of your GraphQL schema, typically in mutation/query payloads
 * with an "errors" field. These are used for expected business logic errors like validation
 * failures, constraint violations, or any errors that users can understand and potentially fix.
 * <p>
 * This strategy only handles exceptions that:
 * 1. Occur in operations that return a payload with an "errors" field
 * 2. Match the @error directive configuration in the schema
 * <p>
 * Exceptions not handled here fall through to become top-level errors.
 */
public abstract class SchemaBasedErrorStrategy {

    private final ExceptionStrategyConfiguration configuration;
    private final SchemaErrorMapper schemaErrorMapper;

    public SchemaBasedErrorStrategy(ExceptionStrategyConfiguration configuration,
                                    ExceptionToErrorMappingProvider mappingProvider,
                                    DataAccessExceptionMapper dataAccessExceptionMapper) {
        this.configuration = configuration;
        this.schemaErrorMapper = new SchemaErrorMapper(
                mappingProvider.getDataAccessMappingsForOperation(),
                mappingProvider.getGenericMappingsForOperation(),
                dataAccessExceptionMapper
        );
    }

    /**
     * Attempts to handle an exception as a schema-based error.
     * Returns an Optional containing the error payload if the exception matches schema configuration,
     * or Optional.empty() if the exception should become a top-level error instead.
     */
    public Optional<CompletableFuture<Object>> handleException(DataFetchingEnvironment environment, Throwable thrownException) {
        String operationName = environment.getFieldDefinition().getName();

        // Check if this exception type is configured for handling
        for (var entry : configuration.getFieldsForException().entrySet()) {
            Class<? extends Throwable> exceptionType = entry.getKey();
            if (exceptionType.isInstance(thrownException) &&
                    entry.getValue().contains(operationName)) {

                // Delegate to specific handler based on exception type
                if (thrownException instanceof ValidationViolationGraphQLException) {
                    return handleValidationException(
                            (ValidationViolationGraphQLException) thrownException,
                            operationName);
                } else if (thrownException instanceof IllegalArgumentException) {
                    return handleIllegalArgumentException(
                            (IllegalArgumentException) thrownException,
                            operationName,
                            environment.getExecutionStepInfo().getPath());
                } else if (thrownException instanceof DataAccessException) {
                    return handleDataAccessException(
                            (DataAccessException) thrownException,
                            operationName);
                } else {
                    return handleBusinessLogicException(thrownException, operationName);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Handle validation exceptions. Must be implemented by subclasses.
     */
    public abstract Optional<CompletableFuture<Object>> handleValidationException(ValidationViolationGraphQLException e, String operationName);

    /**
     * Handle illegal argument exceptions. Must be implemented by subclasses.
     */
    public abstract Optional<CompletableFuture<Object>> handleIllegalArgumentException(IllegalArgumentException e, String operationName, ResultPath path);

    protected Optional<CompletableFuture<Object>> handleDataAccessException(DataAccessException e, String operationName) {
        Object error = schemaErrorMapper.mapDataAccessException(
                e,
                operationName,
                this::createDefaultDataAccessError
        );
        return createPayload(operationName, List.of(error));
    }

    protected Optional<CompletableFuture<Object>> handleBusinessLogicException(Throwable e, String operationName) {
        return schemaErrorMapper.mapBusinessLogicException(e, operationName)
                .flatMap(error -> createPayload(operationName, List.of(error)));
    }

    /**
     * Create a payload containing the errors for the operation.
     */
    protected Optional<CompletableFuture<Object>> createPayload(String operationName, List<?> errors) {
        return Optional.ofNullable(configuration.getPayloadForField().get(operationName))
                .map(creator -> creator.createPayload(errors))
                .map(CompletableFuture::completedFuture);
    }

    /**
     * Create a default error for data access exceptions.
     * Can be overridden by subclasses to customize the default error format.
     */
    protected abstract Object createDefaultDataAccessError(String operationName, String message);
}