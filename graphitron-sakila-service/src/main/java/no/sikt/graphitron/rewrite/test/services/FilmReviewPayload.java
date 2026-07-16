package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: mutation-side counterpart to {@link FilmLookupPayload}. The carrier classifier
 * resolves an {@code ErrorChannel} against this record's canonical
 * {@code (Integer reviewId, List<?> errors)} constructor; on a thrown exception,
 * {@code ErrorRouter.dispatch} synthesises {@code new FilmReviewPayload(null, errors)} via the
 * per-fetcher catch arm on the emitted {@code MutationServiceRecordField} fetcher.
 *
 * <p>The errors-slot type is {@code List<?>} to match the dispatch lambda's
 * {@code Function<List<?>, P>} parameter (the planned {@code List<Object>} migration is part
 * of the source-direct unwind still to come).
 */
public record FilmReviewPayload(Integer reviewId, List<?> errors) {}
