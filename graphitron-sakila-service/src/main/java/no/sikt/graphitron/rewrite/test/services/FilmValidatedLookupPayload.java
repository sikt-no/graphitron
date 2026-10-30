package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture: the payload of a {@code @error} channel carrying a {@code {handler: VALIDATION}} entry
 * beside a dispatch handler. Same shape as {@link FilmLookupPayload} (a defaulted {@code title}
 * slot plus the errors slot as {@code List<?>}, so the dispatch lambda's
 * {@code Function<List<?>, P>} parameter stays substitutable) and deliberately a separate type, so
 * the VALIDATION handler stays off the fixtures that exercise dispatch. The field it serves takes a
 * scalar, so no validation pre-step is emitted for it: the pre-step validates only the classes and
 * jOOQ records a {@code @service} method takes.
 *
 * <p>What this pins is the emitted {@code message} body's {@code GraphQLError} arm. The
 * validation path puts {@code GraphQLError} instances in the errors slot which need not be
 * {@code Throwable}s, so that arm is the one resolution step no dispatch fixture can reach; it
 * has no execute-tier cover otherwise, and the alternative pin (asserting on emitted code text)
 * is a banned form. See {@link FilmValidatedLookupService}.
 */
public record FilmValidatedLookupPayload(String title, List<?> errors) {}
