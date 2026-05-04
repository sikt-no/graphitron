package no.sikt.graphitron.rewrite.schema.input;

/**
 * Thrown by {@link SchemaInputAttribution#build} when the supplied list of
 * {@link SchemaInput} entries violates a rewrite-core invariant (e.g. the
 * same {@code sourceName} declared in two entries).
 *
 * <p>Distinct exception type so tests can catch precisely on the attribution
 * boundary without swallowing unrelated runtime failures.
 */
public class SchemaInputException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public SchemaInputException(String message) {
        super(message);
    }
}
