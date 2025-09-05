package no.sikt.graphql.exception;

import java.util.List;

public class GenericExceptionContentToErrorMapping implements ExceptionContentToErrorMapping {
    final private GenericExceptionMatcher genericExceptionMappingContent;
    final private ErrorHandler errorHandler;

    public GenericExceptionContentToErrorMapping(GenericExceptionMatcher genericExceptionMappingContent, ErrorHandler errorHandler) {
        this.genericExceptionMappingContent = genericExceptionMappingContent;
        this.errorHandler = errorHandler;
    }

    public boolean matches(Throwable exception) {
        return genericExceptionMappingContent.matches(exception);
    }

    @Override
    public Object handleError(List<String> path, String defaultMessage) {
        return errorHandler.handleError(path, defaultMessage);
    }

}
