package no.fellesstudentsystem.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.util.List;

public class DataAccessExceptionContentToErrorMapping {
    final private DataAccessExceptionMappingContent dataAccessExceptionMapping;
    final private ErrorHandler errorHandler;

    public DataAccessExceptionContentToErrorMapping(DataAccessExceptionMappingContent dataAccessExceptionMapping, ErrorHandler errorHandler) {
        this.dataAccessExceptionMapping = dataAccessExceptionMapping;
        this.errorHandler = errorHandler;
    }

    public boolean matches(DataAccessException exception) {
        return dataAccessExceptionMapping.matches(exception);
    }

    public Object handleError(List<String> path) {
        return errorHandler.handleError(path);
    }

    @FunctionalInterface
    static
    public interface ErrorHandler {
        Object handleError(List<String> path);
    }
}
