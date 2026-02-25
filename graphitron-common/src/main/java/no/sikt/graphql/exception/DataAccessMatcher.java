package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.sql.SQLException;

/**
 * Matcher for DataAccessException that checks SQL error code, SQL state, and message content.
 * This class extends GenericExceptionMatcher to add SQL-specific matching logic.
 */
public class DataAccessMatcher extends GenericExceptionMatcher {
    private final String errorCode;
    private final String sqlState;

    public DataAccessMatcher(String errorCode, String sqlState, String substringOfExceptionMessage) {
        super(DataAccessException.class.getName(), substringOfExceptionMessage);
        this.errorCode = errorCode;
        this.sqlState = sqlState;
    }

    boolean matches(DataAccessException exception) {
        SQLException sqlException = exception.getCause(SQLException.class);

        if (sqlException == null) {
            return false;
        }

        boolean codeMatches = errorCode == null || errorCode.equals(Integer.toString(sqlException.getErrorCode()));
        boolean sqlStateMatches = sqlState == null || sqlState.equals(sqlException.getSQLState());
        return codeMatches && sqlStateMatches && super.matches(exception);
    }
}
