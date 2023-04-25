package no.fellesstudentsystem.graphitron.exceptions;

import org.jooq.exception.DataAccessException;

public class TestExceptionCause extends DataAccessException {

    private String name;

    public TestExceptionCause(String message) {
        super(message);
    }

    public TestExceptionCause(String message, Throwable cause) {
        super(message, cause);
    }

    public String getCauseField() {
        return name;
    }

    public void setCauseField(String name) {
        this.name = name;
    }
}
