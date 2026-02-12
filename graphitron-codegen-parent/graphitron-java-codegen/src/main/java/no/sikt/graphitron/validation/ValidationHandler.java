package no.sikt.graphitron.validation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.Set;

public class ValidationHandler {
    private static final Set<String> errorMessages = new LinkedHashSet<>();
    private static final Set<String> warningMessages = new LinkedHashSet<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationHandler.class);

    /**
     * Adds an error message that will be thrown at a later time
     * @param formatedErrorMessage - A formated description of the error.
     * @param args - The arguments for the formatedErrorMessage
     */
    public static void addErrorMessage(String formatedErrorMessage, Object... args) {
        errorMessages.add(String.format(formatedErrorMessage, args));
    }

    /**
     * Adds a warning message that will be logged.
     * @param formatedWarningMessage - A formated description of the warning
     * @param args - Arguments for the formatedWarningMessage
     */
    public static void addWarningMessage(String formatedWarningMessage, Object... args) {
        warningMessages.add(String.format(formatedWarningMessage, args));
    }

    /**
     * Adds an error message and immediately throws an InvalidSchemaException for all added error messages.
     * @param formatedErrorMessage - A description of the error as a formated string.
     * @param args - The arguments referenced in the formatedErrorMessage
     */
    public static void addErrorMessageAndThrow(String formatedErrorMessage, Object... args) {
        addErrorMessage(formatedErrorMessage, args);
        throw new InvalidSchemaException("Problems have been found that prevent code generation: \n" + String.join("\n", errorMessages));
    }

    public static InvalidSchemaException getException() {
        return new InvalidSchemaException("Problems have been found that prevent code generation: \n" + String.join("\n", errorMessages));
    }

    /**
     * Logs all reported warnings.
     */
    public static void logWarnings() {
        if (!warningMessages.isEmpty()) {
            LOGGER.warn("Problems have been found that MAY prevent code generation:\n{}", String.join("\n", warningMessages));
        }
    }

    /**
     * Throws an exception if any error has previously been reported.
     */
    public static void throwIfErrors() {
        if (!errorMessages.isEmpty()) {
            throw new InvalidSchemaException("Problems have been found that prevent code generation: \n" + String.join("\n", errorMessages));
        }
    }

    /**
     * Used to clear the errorMessages between tests.
     */
    public static void resetErrorMessages() {
        errorMessages.clear();
    }

    /**
     * Used to clear warningMessages between tests.
     */
    public static void resetWarningMessages() {
        warningMessages.clear();
    }

    /**
     * format Asserts that an expression is true and throws a InvalidSchemaException if the expression is false.
     * @param expr - The expression to be checked.
     * @param message - A parameterized string describing the error
     * @param values - Values for the parameterized string.
     */
    public static void isTrue (boolean expr, String message, Object ... values) {
        if (!expr) {
            throw new InvalidSchemaException(String.format(message, values));
        }
    }
}