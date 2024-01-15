package no.fellesstudentsystem.graphql.exception;

import org.jooq.exception.DataAccessException;
import org.jooq.exception.SQLStateClass;

import java.sql.SQLException;
import java.util.Optional;

public class DataAccessExceptionMappingContent {
    private final SQLStateClass sqlStateClass;
    private final String errorCode;
    private final String substringOfExceptionMessage;

    public DataAccessExceptionMappingContent(SQLStateClass sqlStateClass, String errorCode, String substringOfExceptionMessage) {
        this.sqlStateClass = sqlStateClass;
        this.errorCode = errorCode;
        this.substringOfExceptionMessage = substringOfExceptionMessage;
    }

     boolean matches(DataAccessException exception) {
        SQLException sqlException = exception.getCause(SQLException.class);

        if (sqlException == null) {
            return false;
        }

        return this.sqlStateClass == exception.sqlStateClass() &&
                this.errorCode.equals(Integer.toString(sqlException.getErrorCode())) &&
                Optional.ofNullable(this.substringOfExceptionMessage)
                        .map(substring -> exception.getMessage().contains(substring))
                        .orElse(true);
    }
}
