package no.sikt.graphql.exception;

import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import graphql.execution.SimpleDataFetcherExceptionHandler;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Handles exceptions that should become top-level GraphQL errors.
 * <p>
 * Top-level errors appear in the standard GraphQL "errors" array at the root of the response.
 * These are used for technical/exceptional issues like system failures, authentication errors,
 * or any unhandled exceptions that aren't part of normal business logic flow.
 * <p>
 * Exceptions that reach this handler were not handled by SchemaBasedErrorStrategy,
 * meaning they don't have corresponding @error directives in the schema.
 * <p>
 * When a DataLoader batch fails, the same exception object is delivered to every field in that batch. Each exception
 * object is therefore given one id, shared by every field it failed, and is logged only the first time it is seen.
 * <p>
 * The message of a {@link DataAccessException} contains the full SQL, and the driver's causes may repeat it. It is
 * therefore not logged as-is at ERROR level. Instead, a summary with the SQL state, vendor code and the driver's own
 * message is logged, while the full exception is only logged at DEBUG level.
 */
public class TopLevelErrorHandler extends SimpleDataFetcherExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TopLevelErrorHandler.class);

    /**
     * The maximum number of characters of the driver message that is included in the ERROR log line.
     */
    protected static final int MAX_LOGGED_MESSAGE_LENGTH = 1000;

    private final DataAccessExceptionMapper dataAccessExceptionMapper;

    /**
     * Ids of exceptions that have already been logged. Throwable does not override equals/hashCode, so this is keyed
     * on identity. Weak keys let the exceptions be garbage collected once the request is done.
     */
    private final Map<Throwable, UUID> exceptionIds = Collections.synchronizedMap(new WeakHashMap<>());

    public TopLevelErrorHandler(DataAccessExceptionMapper dataAccessExceptionMapper) {
        this.dataAccessExceptionMapper = dataAccessExceptionMapper;
    }

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(DataFetcherExceptionHandlerParameters handlerParameters) {
        Throwable exception = unwrap(handlerParameters.getException());
        List<GraphQLError> errors;

        if (exception instanceof ValidationViolationGraphQLException) {
            errors = ((ValidationViolationGraphQLException) exception).getUnderlyingErrors();
        } else if (exception instanceof IllegalArgumentException || exception instanceof NoRowsAffectedException) {
            errors = List.of(GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
                    .message(exception.getMessage())
                    .build());
        } else if (exception instanceof DataAccessException) {
            var exceptionID = getOrRegisterExceptionId(exception, handlerParameters, id -> {
                LOGGER.error(
                        "DataAccessException with id {} caused an unhandled, generic GraphQL-error at {}: {}",
                        id,
                        handlerParameters.getPath(),
                        describeDataAccessException((DataAccessException) exception)
                );
                LOGGER.debug("Full DataAccessException with id {}:", id, exception);
            });
            errors = List.of(
                    GraphqlErrorBuilder.newError(handlerParameters.getDataFetchingEnvironment())
                            .message("An exception occurred. The error has been logged with id " + exceptionID + ": " + dataAccessExceptionMapper.getMsgFromException((DataAccessException) exception))
                            .build()
            );
        } else {
            var exceptionID = getOrRegisterExceptionId(exception, handlerParameters, id ->
                    LOGGER.error("Exception with id {} caused an unhandled, generic GraphQL-error at {}: ", id, handlerParameters.getPath(), exception)
            );

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

    /**
     * Summarise a {@link DataAccessException} for the ERROR log without the SQL. Override this to further restrict what
     * is logged, for example to remove personal data that the database includes in some of its messages.
     *
     * @param exception The exception to summarise.
     * @return A single line with the exception type, SQL state, vendor code and the (truncated) driver message.
     */
    protected String describeDataAccessException(DataAccessException exception) {
        var description = new StringBuilder(exception.getClass().getName());
        var sqlException = exception.getCause(SQLException.class);
        if (sqlException == null) {
            // Without a driver exception the message comes from jOOQ itself. It is still truncated to bound its size.
            return description.append(": ").append(truncate(exception.getMessage())).toString();
        }

        return description
                .append(" [SQL state: ").append(sqlException.getSQLState())
                .append(", vendor code: ").append(sqlException.getErrorCode())
                .append("] ")
                .append(sqlException.getClass().getName())
                .append(": ")
                .append(truncate(sqlException.getMessage()))
                .toString();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }

        var singleLine = message.replaceAll("\\s+", " ").trim();
        if (singleLine.length() <= MAX_LOGGED_MESSAGE_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, MAX_LOGGED_MESSAGE_LENGTH) + "... (truncated, " + singleLine.length() + " characters)";
    }

    private UUID getOrRegisterExceptionId(Throwable exception, DataFetcherExceptionHandlerParameters handlerParameters, Consumer<UUID> logFirstOccurrence) {
        var isFirstOccurrence = new boolean[]{false};
        var id = exceptionIds.computeIfAbsent(exception, it -> {
            isFirstOccurrence[0] = true;
            return UUID.randomUUID();
        });

        if (isFirstOccurrence[0]) {
            logFirstOccurrence.accept(id);
        } else {
            LOGGER.debug("Exception with id {} also caused an error at {}", id, handlerParameters.getPath());
        }
        return id;
    }
}
