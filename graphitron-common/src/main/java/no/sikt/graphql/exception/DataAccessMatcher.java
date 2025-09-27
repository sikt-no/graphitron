package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.sql.SQLException;

/**
 * Matcher for DataAccessException that checks both SQL error code and message content.
 * This class extends GenericExceptionMatcher to add SQL-specific matching logic.
 */
public class DataAccessMatcher extends GenericExceptionMatcher {
    private final String errorCode;

    public DataAccessMatcher(String errorCode, String substringOfExceptionMessage) {
        super(DataAccessException.class.getName(), substringOfExceptionMessage);
        this.errorCode = errorCode;
    }

    boolean matches(DataAccessException exception) {
        SQLException sqlException = exception.getCause(SQLException.class);

        if (sqlException == null) {
            return false;
        }

        return this.errorCode.equals(Integer.toString(sqlException.getErrorCode())) && super.matches(exception);
    }
}