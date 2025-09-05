package no.sikt.graphql.exception;

import org.jooq.exception.DataAccessException;

public interface DataAccessExceptionMapper {

    default String getMsgFromException(DataAccessException exception) {
        return exception.getMessage();
    }
}
