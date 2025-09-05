package no.sikt.graphql.exception;

import graphql.execution.AsyncExecutionStrategy;
import graphql.execution.ExecutionStrategyParameters;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Custom execution strategy that routes exceptions to either schema-based or top-level errors.
 * <p>
 * This is the main entry point for exception handling in the GraphQL execution layer.
 * It first attempts to handle exceptions as schema-based errors (via SchemaBasedErrorStrategy),
 * and if that's not possible, falls back to top-level errors (via TopLevelErrorHandler).
 */
public class CustomExecutionStrategy extends AsyncExecutionStrategy {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomExecutionStrategy.class);

    private final SchemaBasedErrorStrategy schemaBasedErrorStrategy;

    public CustomExecutionStrategy(TopLevelErrorHandler topLevelErrorHandler, SchemaBasedErrorStrategy schemaBasedErrorStrategy) {
        super(topLevelErrorHandler);
        this.schemaBasedErrorStrategy = schemaBasedErrorStrategy;
    }

    @Override
    protected <T> CompletableFuture<T> handleFetchingException(DataFetchingEnvironment environment, ExecutionStrategyParameters parameters, Throwable e) {
        Optional<CompletableFuture<Object>> schemaBasedResult = schemaBasedErrorStrategy.handleException(environment, e);

        if (schemaBasedResult.isPresent()) {
            LOGGER.debug("Exception handled as schema-based error:", e);
            return (CompletableFuture<T>) schemaBasedResult.get();
        }

        LOGGER.debug("Exception will become top-level error:", e);
        return super.handleFetchingException(environment, parameters, e);
    }
}