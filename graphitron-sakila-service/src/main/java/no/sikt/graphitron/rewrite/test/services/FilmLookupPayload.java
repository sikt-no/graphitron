package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * R12 fixture: free-form {@code @record} payload with an {@code errors} slot, exercising the
 * carrier classifier's {@code ErrorChannel} resolution end-to-end against a real
 * {@code @service}-backed query and PostgreSQL. The canonical (all-fields) constructor exposes
 * one defaulted slot ({@code title}) plus the errors slot ({@code List<?>}); on a thrown
 * exception, {@code ErrorRouter.dispatch} synthesises
 * {@code new FilmLookupPayload(null, errors)} via the per-fetcher catch arm.
 *
 * <p>The errors-slot type is {@code List<?>} to match the dispatch lambda's
 * {@code Function<List<?>, P>} parameter. R12's "Test fixture updates for source-direct
 * dispatch" Remaining-work bullet plans to migrate this to {@code List<Object>} as part of
 * narrowing the source-direct contract; until that lands, {@code List<?>} keeps the lambda
 * substitutable. The matched {@code Throwable} is placed directly into the list (no
 * developer-supplied error data class); graphql-java's {@code PropertyDataFetcher} reads
 * {@code errors} off this record while the synthesized per-{@code @error}-type
 * {@code DataFetcher}s populate {@code path} and {@code message} from
 * {@link Throwable#getMessage()} / the GraphQL execution path.
 */
public record FilmLookupPayload(String title, List<?> errors) {}
