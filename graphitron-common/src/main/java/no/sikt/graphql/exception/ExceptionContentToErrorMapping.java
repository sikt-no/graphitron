package no.sikt.graphql.exception;

import java.util.List;

public interface ExceptionContentToErrorMapping {
    Object handleError(List<String> path, String defaultMessage);

    @FunctionalInterface
    interface ErrorHandler {
        Object handleError(List<String> path, String defaultMessage);
    }
}
