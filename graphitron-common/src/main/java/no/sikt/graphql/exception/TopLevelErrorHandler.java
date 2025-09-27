package no.sikt.graphql.exception;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.SimpleDataFetcherExceptionHandler;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Handles exceptions that should become top-level GraphQL errors.
 * <p>
 * Top-level errors appear in the standard GraphQL "errors" array at the root of the response.
 * These are used for technical/exceptional issues like system failures, authentication errors,
 * or any unhandled exceptions that aren't part of normal business logic flow.
 * <p>
 * Exceptions that reach this handler were not handled by SchemaBasedErrorStrategy,
 * meaning they don't have corresponding @error directives in the schema.
 */
public class TopLevelErrorHandler extends SimpleDataFetcherExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TopLevelErrorHandler.class);

    private final DataAccessExceptionMapper dataAccessExceptionMapper;

    public TopLevelErrorHandler(DataAccessExceptionMapper dataAccessExceptionMapper) {
        this.dataAccessExceptionMapper = dataAccessExceptionMapper;
    }

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(DataFetcherExceptionHandlerParameters handlerParameters) {
        Throwable exception = unwrap(handlerParameters.getException());
        List<GraphQLError> errors;

        if (exception instanceof ValidationViolationGraphQLException) {
            errors = ((ValidationViolationGraphQLException) exception).getUnderlyingErrors();
        } else if (exception instanceof IllegalArgumentException) {
            errors = List.of(GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
                    .message(exception.getMessage())
                    .build());
        } else if (exception instanceof DataAccessException) {
            var exceptionID = UUID.randomUUID();
            LOGGER.error("DataAccessException with id {} caused an unhandled, generic GraphQL-error: ", exceptionID, exception);
            errors = List.of(
                    GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
                            .message("An exception occurred. The error has been logged with id " + exceptionID + ": " + dataAccessExceptionMapper.getMsgFromException((DataAccessException) exception))
                            .build()
            );
        } else {
            var exceptionID = UUID.randomUUID();
            LOGGER.error("Exception with id {} caused an unhandled, generic GraphQL-error: ", exceptionID, exception);

            // Return a generic exception message that does not expose the internal cause
            errors = List.of(
                    GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
                            .message("An exception occurred. The error has been logged with id " + exceptionID + ".")
                            .build()
            );
        }
        return CompletableFuture.completedFuture(
                DataFetcherExceptionHandlerResult.newResult()
                        .errors(errors)
                        .build());
    }
}
