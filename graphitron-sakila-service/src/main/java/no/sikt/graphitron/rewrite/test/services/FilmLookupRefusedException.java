package no.sikt.graphitron.rewrite.test.services;

import graphql.GraphqlErrorException;

/**
 * Fixture: an author exception extending {@link GraphqlErrorException}, so it is both a
 * {@code Throwable} and a {@code GraphQLError}, the shape the generated
 * {@code GraphitronClientException} takes. That class is generated after this module compiles, so
 * this one stands in for it.
 *
 * <p>Thrown by {@link FilmRefusedLookupService}, whose errors union lists a
 * {@code {handler: VALIDATION}} member ahead of the {@code GENERIC} member naming this class.
 * Dispatch gives the throw to the {@code GENERIC} member, because {@code VALIDATION} mints no
 * mapping; the union's type resolver must report that member too, rather than letting the
 * {@code VALIDATION} member's {@code GraphQLError} test claim it first.
 */
public class FilmLookupRefusedException extends GraphqlErrorException {

    private static final long serialVersionUID = 1L;

    public FilmLookupRefusedException(String message) {
        super(GraphqlErrorException.newErrorException().message(message));
    }
}
