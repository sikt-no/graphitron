package no.sikt.graphitron.rewrite;

/**
 * R261 site-A fixture: a consumer input bean whose scalar member ({@code filmId}) is declared as
 * {@code Long}, bound to an SDL {@code Int} input field. graphql-java coerces {@code Int} to
 * {@code Integer}, so the generated {@code (Long) map.get("filmId")} would throw
 * {@code ClassCastException} on the first request. The wire-coercion predicate must reject this at
 * generation time with {@code WireCoercionError.Assignability}.
 */
public record TestWireLongBean(Long filmId) {}
