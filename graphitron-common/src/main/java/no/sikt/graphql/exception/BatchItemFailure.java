package no.sikt.graphql.exception;

import java.util.List;
import java.util.Objects;

/**
 * One element of a batch operation that failed, and where in the operation's input that element sat.
 * <p>
 * The path is the addressing scheme the schema's {@code Error} interface already mandates, so a client reads
 * "which item failed" the same way whether the failure came from record validation or from the work itself. It
 * runs from the operation field down to the element, for example {@code ["updateCustomers", "in", "2"]}.
 *
 * @param path  Path from the operation field to the element that failed.
 * @param cause The exception that element failed with.
 */
public record BatchItemFailure(List<String> path, Throwable cause) {
    public BatchItemFailure {
        path = List.copyOf(Objects.requireNonNull(path, "path == null"));
        Objects.requireNonNull(cause, "cause == null");
    }
}
