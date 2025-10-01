package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.util.List;

public class DataAccessExceptionContentToErrorMapping implements ExceptionContentToErrorMapping {
    final private DataAccessMatcher dataAccessExceptionMapping;
    final private ErrorHandler errorHandler;

    public DataAccessExceptionContentToErrorMapping(DataAccessMatcher dataAccessExceptionMapping, ErrorHandler errorHandler) {
        this.dataAccessExceptionMapping = dataAccessExceptionMapping;
        this.errorHandler = errorHandler;
    }

    public boolean matches(DataAccessException exception) {
        return dataAccessExceptionMapping.matches(exception);
    }

    @Override
    public Object handleError(List<String> path, String defaultMessage) {
        return errorHandler.handleError(path, defaultMessage);
    }
}
