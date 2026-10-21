package no.sikt.graphitron.rewrite.test.services;

import java.util.List;

/**
 * Fixture for {@code @error} channel generation: a free-form {@code @record} payload with an
 * {@code errors} field, exercising the {@code Outcome} catch end-to-end against a real
 * {@code @service}-backed query and PostgreSQL. The service returns it on success; on a claimed
 * throw the fetcher returns the {@code Outcome}'s error arm, whose matched {@code Throwable}s the
 * {@code errors} field reads (no developer-supplied error data class), while the synthesized
 * per-{@code @error}-type {@code DataFetcher}s populate {@code path} and {@code message} from
 * {@link Throwable#getMessage()} / the GraphQL execution path. Graphitron never constructs this
 * record.
 */
public record FilmLookupPayload(String title, List<?> errors) {}
