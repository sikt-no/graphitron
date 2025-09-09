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
     * @param errorMessage - A description of the error.
     */
    public static void addErrorMessage(String errorMessage) {
        errorMessages.add(errorMessage);
    }

    /**
     * Adds a warning message that will be logged.
     * @param warningMessage - A description of the warning
     */
    public static void addWarningMessage(String warningMessage) {
        warningMessages.add(warningMessage);
    }

    /**
     * Adds an error message and immediately throws an InvalidSchemaException for all added error messages.
     * @param errorMessage - A description of the error.
     */

    public static void addErrorMessageAndThrow(String errorMessage){
        addErrorMessage(errorMessage);
        throw new InvalidSchemaException("Problems have been found that prevent code generation: \n" + String.join("\n", errorMessages));
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
    public static void throwIfErrors () {
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
     *  Asserts that an expression is true and throws a InvalidSchemaExcpetion if the expression is false.
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