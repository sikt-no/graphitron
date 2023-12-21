package no.fellesstudentsystem.graphql.exception;

import graphql.GraphQLError;
import graphql.execution.AbortExecutionException;

import java.util.Collection;

public class ValidationViolationGraphQLException extends AbortExecutionException {

    public ValidationViolationGraphQLException(Collection<GraphQLError> validationErrors) {
        super(validationErrors);
    }
}
