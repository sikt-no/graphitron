package no.sikt.graphitron.rewrite.test.services;

/**
 * R12 fixture: sibling of {@link FilmReviewBadRatingException}. The two exist together so the
 * union's TypeResolver dispatch ladder has multi-branch coverage on the mutation side
 * (mirroring the query-side {@code FilmLookupError} fixture).
 */
public class FilmReviewMissingFilmException extends RuntimeException {
    public FilmReviewMissingFilmException(String message) {
        super(message);
    }
}
