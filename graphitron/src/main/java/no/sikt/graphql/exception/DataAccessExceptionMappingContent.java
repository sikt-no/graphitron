package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

import java.sql.SQLException;

public class DataAccessExceptionMappingContent extends GenericExceptionMappingContent {
    private final String errorCode;

    public DataAccessExceptionMappingContent(String errorCode, String substringOfExceptionMessage) {
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
