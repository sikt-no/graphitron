package no.sikt.graphql.exception;

import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;

import java.io.Serial;
import java.util.Collection;

public class ValidationViolationGraphQLException extends AbortExecutionException {
    @Serial
    private static final long serialVersionUID = 1L;

    public ValidationViolationGraphQLException(Collection<GraphQLError> validationErrors) {
        super(validationErrors);
    }
}
