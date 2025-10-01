package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

/**
 * Implementation of DataAccessExceptionMapper that uses ErrorMessageFormatter
 * to provide consistent and user-friendly error messages for database exceptions.
 */
public class DataAccessExceptionMapperImpl implements DataAccessExceptionMapper {

    private final ErrorMessageFormatter formatter;
    private final boolean includeDebugInfo;

    /**
     * Create a mapper with default settings (no debug info).
     */
    public DataAccessExceptionMapperImpl() {
        this(new ErrorMessageFormatter(), false);
    }

    /**
     * Create a mapper with custom formatter and debug settings.
     *
     * @param formatter        The error message formatter to use
     * @param includeDebugInfo Whether to include technical details in error messages
     */
    public DataAccessExceptionMapperImpl(ErrorMessageFormatter formatter, boolean includeDebugInfo) {
        this.formatter = formatter;
        this.includeDebugInfo = includeDebugInfo;
    }

    @Override
    public String getMsgFromException(DataAccessException exception) {
        String userMessage = formatter.formatDataAccessException(exception);

        if (includeDebugInfo) {
            return appendDebugInfo(userMessage, exception);
        }

        return userMessage;
    }

    /**
     * Append debug information to the error message.
     * Useful in development environments.
     */
    private String appendDebugInfo(String message, DataAccessException exception) {
        StringBuilder debugMessage = new StringBuilder(message);

        var sqlException = exception.getCause(java.sql.SQLException.class);
        if (sqlException != null) {
            debugMessage.append(" [SQL State: ").append(sqlException.getSQLState());
            debugMessage.append(", Error Code: ").append(sqlException.getErrorCode()).append("]");
        }

        return debugMessage.toString();
    }
}