package no.fellesstudentsystem.graphitron.exceptions;

import org.jooq.exception.DataAccessException;

public class TestException extends DataAccessException {

    public TestException(String message) {
        super(message);
    }

    public TestException(String message, Throwable cause) {
        super(message, cause);
    }
}
