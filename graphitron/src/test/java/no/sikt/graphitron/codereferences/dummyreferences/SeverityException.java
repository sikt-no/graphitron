package no.sikt.graphitron.codereferences.dummyreferences;

/**
 * An {@code @error} handler source class with an accessor beyond {@code message}: an error type
 * declaring an extra {@code severity} field reads it here, so a channel whose handler claims this
 * class passes the handler accessor check.
 */
public class SeverityException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public SeverityException(String message) {
        super(message);
    }

    public String getSeverity() {
        return "LOW";
    }
}
