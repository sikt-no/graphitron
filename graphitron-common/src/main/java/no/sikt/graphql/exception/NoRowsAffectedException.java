package no.sikt.graphql.exception;

/**
 * Thrown when a statement in a batched write reports that it affected no rows.
 * <p>
 * A write that reaches no rows is not an error to the database. A row filtered away by a row level security
 * policy, an id matching nothing, and a row deleted concurrently all produce valid SQL that changes nothing.
 * Mutations therefore have to check the affected row count themselves, because the payload they return is
 * built by a separate read that still finds the untouched row and would report the mutation as successful.
 * <p>
 * The check cannot tell why the count did not match, only that it did not.
 * <p>
 * Schemas may map this to an error type of their own with the {@code @error} directive, matching on this
 * class name. Left unmapped, {@link TopLevelErrorHandler} puts the message in the top level {@code errors}
 * array.
 */
public class NoRowsAffectedException extends RuntimeException {
    public NoRowsAffectedException(String message) {
        super(message);
    }
}
