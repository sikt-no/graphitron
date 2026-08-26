package no.sikt.graphql.exception;

import graphql.execution.ResultPath;
import graphql.schema.DataFetchingEnvironment;
import org.jooq.exception.DataAccessException;

import java.util.ArrayList;
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

        // Unwrap CompletionException from async operations
        Throwable exception = unwrapCompletionException(thrownException);

        // Check if this exception type is configured for handling
        for (var entry : configuration.getFieldsForException().entrySet()) {
            Class<? extends Throwable> exceptionType = entry.getKey();
            if (exceptionType.isInstance(exception) &&
                    entry.getValue().contains(operationName)) {

                // Delegate to specific handler based on exception type
                if (exception instanceof ValidationViolationGraphQLException) {
                    return handleValidationException(
                            (ValidationViolationGraphQLException) exception,
                            operationName);
                } else if (exception instanceof IllegalArgumentException) {
                    return handleIllegalArgumentException(
                            (IllegalArgumentException) exception,
                            operationName,
                            environment.getExecutionStepInfo().getPath());
                } else if (exception instanceof PartialBatchFailureException) {
                    return handlePartialBatchFailure(
                            (PartialBatchFailureException) exception,
                            operationName);
                } else if (exception instanceof DataAccessException) {
                    return handleDataAccessException(
                            (DataAccessException) exception,
                            operationName);
                } else {
                    return handleBusinessLogicException(exception, operationName);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Unwraps CompletionException from async operations.
     * If the exception is a CompletionException with a cause, returns the cause.
     * Otherwise, returns the exception itself.
     */
    private Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
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
     * Handle a batch where some elements succeeded and some did not, by reporting the failures on the payload
     * that was already built from the elements that succeeded.
     * <p>
     * Every failure has to be representable as a schema error for this to work. If any one of them has no
     * {@code @error} mapping to be reported through, reporting a subset would hide the rest, so the whole
     * operation falls through to a top-level error instead, which is what it did before per-item reporting
     * existed.
     */
    protected Optional<CompletableFuture<Object>> handlePartialBatchFailure(PartialBatchFailureException e, String operationName) {
        var errors = new ArrayList<>();
        for (var failure : e.getFailures()) {
            var error = mapItemFailure(failure, operationName);
            if (error.isEmpty()) {
                return Optional.empty();
            }
            errors.add(error.get());
        }
        return attachErrors(operationName, e.getPayload(), errors);
    }

    /**
     * @return The schema error for one failed batch element, reported at that element's own path, or empty if
     * the schema has no error type this exception maps to.
     */
    private Optional<Object> mapItemFailure(BatchItemFailure failure, String operationName) {
        if (failure.cause() instanceof DataAccessException dataAccessException) {
            return Optional.of(schemaErrorMapper.mapDataAccessException(
                    dataAccessException,
                    operationName,
                    failure.path(),
                    this::createDefaultDataAccessError
            ));
        }
        return schemaErrorMapper.mapBusinessLogicException(failure.cause(), operationName, failure.path());
    }

    /**
     * Create a payload containing the errors for the operation. The payload carries no data, which is what an
     * operation that failed outright should report.
     */
    protected Optional<CompletableFuture<Object>> createPayload(String operationName, List<?> errors) {
        return attachErrors(operationName, null, errors);
    }

    /**
     * Put the errors on a payload that has already been built, so an operation can report both the work that
     * succeeded and the work that did not. Passing {@code null} for the payload builds an empty one, making this
     * the general form of {@link #createPayload}.
     *
     * @param operationName The query or mutation field these errors belong to.
     * @param payload       The payload to put the errors on, or {@code null} for a payload with no data.
     * @param errors        The errors to report.
     * @return The payload, or empty if this operation has no errors field to put them in.
     */
    protected Optional<CompletableFuture<Object>> attachErrors(String operationName, Object payload, List<?> errors) {
        return Optional.ofNullable(configuration.getPayloadForField().get(operationName))
                .map(creator -> creator.attachErrors(payload, errors))
                .map(CompletableFuture::completedFuture);
    }

    /**
     * Create a default error for data access exceptions.
     * Can be overridden by subclasses to customize the default error format.
     */
    protected abstract Object createDefaultDataAccessError(String operationName, String message);

    /**
     * Create a default error for a data access exception that belongs to one element of a batch rather than to
     * the operation as a whole. The default ignores the path and reports the error against the operation, which
     * is what implementations written before per-item reporting existed do. Override it to report the element's
     * own path, so a client can tell which element of the batch failed.
     *
     * @param operationName The query or mutation field this error belongs to.
     * @param path          Path from the operation field down to the element that failed.
     * @param message       The message extracted from the exception.
     */
    protected Object createDefaultDataAccessError(String operationName, List<String> path, String message) {
        return createDefaultDataAccessError(operationName, message);
    }
}