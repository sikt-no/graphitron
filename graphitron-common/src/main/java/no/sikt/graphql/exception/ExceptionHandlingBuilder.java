package no.sikt.graphql.exception;

import graphql.execution.ExecutionStrategy;

/**
 * Builder for creating a fully configured exception handling setup for GraphQL Java.
 * <p>
 * This builder sets up both schema-based (ad-hoc) and top-level error handling.
 * Schema-based errors appear in payload "errors" fields, while top-level errors
 * appear in the GraphQL response's root "errors" array.
 */
public class ExceptionHandlingBuilder {

    private DataAccessExceptionMapper dataAccessMapper;
    private SchemaBasedErrorStrategy schemaBasedStrategy;
    private TopLevelErrorHandler topLevelErrorHandler;


    /**
     * Set the data access exception mapper.
     * This mapper extracts messages from database exceptions.
     */
    public ExceptionHandlingBuilder withDataAccessMapper(DataAccessExceptionMapper dataAccessMapper) {
        this.dataAccessMapper = dataAccessMapper;
        return this;
    }

    /**
     * Set the schema-based error strategy implementation.
     * This handles exceptions that should become errors in payload fields.
     */
    public ExceptionHandlingBuilder withSchemaBasedStrategy(SchemaBasedErrorStrategy strategy) {
        this.schemaBasedStrategy = strategy;
        return this;
    }

    /**
     * Set a custom top-level error handler.
     * If not set, a default handler will be created using the data access mapper.
     * This handles exceptions that don't match schema @error directives.
     */
    public ExceptionHandlingBuilder withTopLevelErrorHandler(TopLevelErrorHandler handler) {
        this.topLevelErrorHandler = handler;
        return this;
    }

    /**
     * Build the configured execution strategy.
     *
     * @return A CustomExecutionStrategy with all components properly wired
     * @throws IllegalStateException if required components are not set
     */
    public ExecutionStrategy build() {
        // Validate required components
        if (schemaBasedStrategy == null) {
            throw new IllegalStateException(
                    "Schema-based error strategy must be provided. Call withSchemaBasedStrategy()."
            );
        }

        // Create top-level error handler if not provided
        if (topLevelErrorHandler == null) {
            if (dataAccessMapper == null) {
                throw new IllegalStateException(
                        "Either provide a TopLevelErrorHandler or a DataAccessExceptionMapper to create the default handler."
                );
            }
            topLevelErrorHandler = new TopLevelErrorHandler(dataAccessMapper);
        }

        return new CustomExecutionStrategy(topLevelErrorHandler, schemaBasedStrategy);
    }
}