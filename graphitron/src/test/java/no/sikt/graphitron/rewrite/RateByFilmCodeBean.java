package no.sikt.graphitron.rewrite;

/** {@link RateByFilmIdBean} with the scalar typed {@code String}, which is not the tenant key's type. */
public record RateByFilmCodeBean(String filmId) {}
