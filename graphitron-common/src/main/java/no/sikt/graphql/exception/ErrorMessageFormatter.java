package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple formatter for database exception error messages.
 * Provides basic user-friendly messages for common SQL errors.
 */
public class ErrorMessageFormatter {

    // Pattern to extract constraint names from database error messages
    private static final Pattern CONSTRAINT_PATTERN = Pattern.compile(
            "constraint\\s+[\"']?([^\"'\\s]+)[\"']?",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extract a user-friendly message from a DataAccessException.
     */
    public String formatDataAccessException(DataAccessException exception) {
        SQLException sqlException = exception.getCause(SQLException.class);

        if (sqlException == null || sqlException.getMessage() == null) {
            return getDefaultMessage(exception);
        }

        String sqlMessage = sqlException.getMessage();
        String sqlState = sqlException.getSQLState();

        // Handle common SQL states with simple messages
        if (sqlState != null) {
            return switch (sqlState) {
                case "23505" -> "Duplicate value not allowed";
                case "23503" -> "Referenced record does not exist";
                case "23502" -> "Required field cannot be empty";
                case "23514" -> formatCheckConstraint(sqlMessage);
                case "22001" -> "Value exceeds maximum length";
                default -> cleanupMessage(sqlMessage);
            };
        }

        return cleanupMessage(sqlMessage);
    }

    /**
     * Format check constraint violation - try to extract the constraint name.
     */
    private String formatCheckConstraint(String sqlMessage) {
        Matcher matcher = CONSTRAINT_PATTERN.matcher(sqlMessage);
        if (matcher.find()) {
            String constraintName = matcher.group(1);
            // Simple cleanup: remove common suffixes and convert to readable format
            String cleaned = constraintName.toLowerCase()
                    .replaceAll("_check$", "")
                    .replace('_', ' ');
            return "Value violates constraint: " + cleaned;
        }
        return "Value does not meet validation requirements";
    }

    /**
     * Basic cleanup of SQL error messages.
     */
    private String cleanupMessage(String message) {
        // Remove common prefixes and normalize
        return message
                .replaceAll("ERROR:\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Get default message when SQL exception is not available.
     */
    private String getDefaultMessage(Throwable exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Database operation failed";
        }
        return cleanupMessage(message);
    }
}