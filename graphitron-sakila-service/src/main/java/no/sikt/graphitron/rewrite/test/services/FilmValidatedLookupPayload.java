package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: the payload of the one {@code @error} channel in the suite carrying a
 * {@code {handler: VALIDATION}} entry. Same shape as {@link FilmLookupPayload} (a defaulted
 * {@code title} slot plus the errors slot as {@code List<?>}, so the dispatch lambda's
 * {@code Function<List<?>, P>} parameter stays substitutable) and deliberately a separate type:
 * a VALIDATION handler puts the wrapper's Jakarta validation pre-step on every fetcher mapping the
 * channel, so keeping it off {@code FilmLookupPayload} keeps that pre-step out of the fixtures
 * that exercise dispatch.
 *
 * <p>What this pins is the emitted {@code message} body's {@code GraphQLError} arm. The
 * validation path puts {@code GraphQLError} instances in the errors slot which need not be
 * {@code Throwable}s, so that arm is the one resolution step no dispatch fixture can reach; it
 * has no execute-tier cover otherwise, and the alternative pin (asserting on emitted code text)
 * is a banned form. See {@link FilmValidatedLookupService}.
 */
public record FilmValidatedLookupPayload(String title, List<?> errors) {}
