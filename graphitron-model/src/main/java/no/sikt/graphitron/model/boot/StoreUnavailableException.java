package no.sikt.graphitron.model.boot;

/**
 * Thrown when a run asked for a persisted store and cannot have the one it asked for.
 *
 * <p>The message is written for the person who has to act on it, because acting on it is the whole
 * reason this is raised rather than answered with a private store nobody asked for. Each site that
 * throws names one cause and one thing to do about it; a run that meets any of them fails, and the
 * person runs it again once they have done that thing.
 *
 * <p>Distinct type so a caller can catch precisely on the store boundary, and so a test can pin
 * which way an open went without reading the build's output.
 */
public class StoreUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public StoreUnavailableException(String message) {
        super(message);
    }

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
