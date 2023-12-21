package no.fellesstudentsystem.graphql.exception;

import java.util.List;

public class DataAccessExceptionContentToErrorMapping {
    final private DataAccessExceptionMappingContent fsDataAccessExceptionMapping;
    final private ErrorHandler errorHandler;

    public DataAccessExceptionContentToErrorMapping(DataAccessExceptionMappingContent fsDataAccessExceptionMapping, ErrorHandler errorHandler) {
        this.fsDataAccessExceptionMapping = fsDataAccessExceptionMapping;
        this.errorHandler = errorHandler;
    }

    public DataAccessExceptionMappingContent getFsDataAccessExceptionMapping() {
        return fsDataAccessExceptionMapping;
    }

    public ErrorHandler getErrorHandler() {
        return errorHandler;
    }

    @FunctionalInterface
    static
    public interface ErrorHandler {
        Object handleError(List<String> path);
    }
}
