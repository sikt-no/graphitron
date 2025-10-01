package no.sikt.graphql.exception;

import java.util.List;

public class GenericExceptionContentToErrorMapping implements ExceptionContentToErrorMapping {
    final private GenericExceptionMatcher genericExceptionMatcher;
    final private ErrorHandler errorHandler;

    public GenericExceptionContentToErrorMapping(GenericExceptionMatcher genericExceptionMatcher, ErrorHandler errorHandler) {
        this.genericExceptionMatcher = genericExceptionMatcher;
        this.errorHandler = errorHandler;
    }

    public boolean matches(Throwable exception) {
        return genericExceptionMatcher.matches(exception);
    }

    @Override
    public Object handleError(List<String> path, String defaultMessage) {
        return errorHandler.handleError(path, defaultMessage);
    }

}
