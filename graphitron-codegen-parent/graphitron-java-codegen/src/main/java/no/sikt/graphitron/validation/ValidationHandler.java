package no.sikt.graphitron.validation;


/*
    This will be a static class where errors are added from various places around Graphitron
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.ValidationException;
import java.util.LinkedHashSet;
import java.util.Set;

public class ValidationHandler {

    private static final Set<String> errorMessages = new LinkedHashSet<>();
    private static final Set<String> warningMessages = new LinkedHashSet<>();
    static final Logger LOGGER = LoggerFactory.getLogger(ValidationHandler.class);


    public static void addErrorMessage(String errorMessage) {
        errorMessages.add(errorMessage);
    }

    public static void addWarningMessage(String warningMessage) {
        warningMessages.add(warningMessage);
    }

    public static void addErrorMessageAndThrow(String errorMessage){
        addErrorMessage(errorMessage);
        throwIfErrors();
    }

    public static void logWarnings() {
        if (!warningMessages.isEmpty()) {
            LOGGER.warn("Problems have been found that MAY prevent code generation:\n{}", warningMessages);
        }
    }

    public static void throwIfErrors () {
        if (!errorMessages.isEmpty()) {
            throw new ValidationException("Problems have been found that prevent code generation: \n" + String.join("\n", errorMessages));
        }
    }

    public static void resetErrorMessages() {
        errorMessages.clear();
    }
    public static void resetWarningMessages() {
        warningMessages.clear();
    }

    public static void isTrue (boolean expr, String message, Object ... values) {
        if (!expr) {
            throw new ValidationException(String.format(message, values));
        }
    }

}