package no.sikt.graphitron.rewrite.test.services;

/**
 * Fixture exception mapped by the {@code OccupantLookupMissingAddress} {@code @error} type
 * (GENERIC handler, matched by class name). Thrown by {@link OccupantsWithErrorsService} to
 * drive the {@code Outcome.ErrorList} arm of the polymorphic-child-under-Outcome fixtures.
 */
public class OccupantLookupMissingAddressException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OccupantLookupMissingAddressException(String message) {
        super(message);
    }
}
