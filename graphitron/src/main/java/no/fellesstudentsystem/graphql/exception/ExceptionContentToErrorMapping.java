package no.fellesstudentsystem.graphql.exception;

import java.io.Serializable;
import java.util.List;

public interface ExceptionContentToErrorMapping {
    Serializable handleError(List<String> path, String defaultMessage);

    @FunctionalInterface
    interface ErrorHandler {
        Serializable handleError(List<String> path, String defaultMessage);
    }
}
