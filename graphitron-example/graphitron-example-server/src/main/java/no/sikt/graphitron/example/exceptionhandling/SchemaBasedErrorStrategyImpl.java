package no.sikt.graphitron.example.exceptionhandling;

import graphql.execution.ResultPath;
import no.sikt.graphitron.example.generated.graphitron.model.InvalidInput;
import no.sikt.graphql.exception.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Implementation of schema-based error strategy for GraphQL operations.
 * Handles validation and illegal argument exceptions by mapping them to InvalidInput errors
 * that appear in the payload's errors field (not as top-level errors).
 */
public class SchemaBasedErrorStrategyImpl extends SchemaBasedErrorStrategy {

    public SchemaBasedErrorStrategyImpl(
            ExceptionStrategyConfiguration configuration, 
            ExceptionToErrorMappingProvider mappingProvider, 
            DataAccessExceptionMapper dataAccessMapper) {
        super(configuration, mappingProvider, dataAccessMapper);
    }

    @Override
    public Optional<CompletableFuture<Object>> handleValidationException(ValidationViolationGraphQLException e, String operationName) {
        List<InvalidInput> errors =
                e.getUnderlyingErrors()
                        .stream()
                        .map(it -> new InvalidInput(
                                it.getPath()
                                        .stream()
                                        .map(Object::toString)
                                        .collect(Collectors.toList()),
                                it.getMessage()))
                        .collect(Collectors.toList());

        return createPayload(operationName, errors);
    }

    @Override
    public Optional<CompletableFuture<Object>> handleIllegalArgumentException(IllegalArgumentException e, String operationName, ResultPath path) {
        return createPayload(operationName, List.of(
                new InvalidInput(
                        path.toList().stream().map(Object::toString).collect(Collectors.toList()),
                        e.getMessage())
                )
        );
    }
    
    @Override
    protected Object createDefaultDataAccessError(String operationName, String message) {
        // Create a default InvalidInput error for data access exceptions
        return new InvalidInput(
                List.of(operationName),
                message
        );
    }
}