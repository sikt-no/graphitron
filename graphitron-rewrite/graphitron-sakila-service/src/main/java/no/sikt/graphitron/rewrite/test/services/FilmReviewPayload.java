package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * R12 fixture: mutation-side counterpart to {@link FilmLookupPayload}. The carrier classifier
 * resolves an {@code ErrorChannel} against this record's canonical
 * {@code (Integer reviewId, List<?> errors)} constructor; on a thrown exception,
 * {@code ErrorRouter.dispatch} synthesises {@code new FilmReviewPayload(null, errors)} via the
 * per-fetcher catch arm on the emitted {@code MutationServiceRecordField} fetcher.
 *
 * <p>The errors-slot type is {@code List<?>} to match the dispatch lambda's
 * {@code Function<List<?>, P>} parameter (R12's planned {@code List<Object>} migration is part
 * of the source-direct unwind in Remaining work).
 */
public record FilmReviewPayload(Integer reviewId, List<?> errors) {}
