package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: mutation-side counterpart to {@link FilmLookupPayload}. The service returns it on
 * success; on a claimed throw the emitted {@code MutationServiceRecordField} fetcher returns the
 * {@code Outcome}'s error arm, and the payload's {@code errors} field reads the claimed exceptions
 * off it. Graphitron never constructs this record; its {@code errors} component is the service's
 * own, which it leaves empty.
 */
public record FilmReviewPayload(Integer reviewId, List<?> errors) {}
