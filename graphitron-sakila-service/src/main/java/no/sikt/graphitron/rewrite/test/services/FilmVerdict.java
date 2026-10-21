package no.sikt.graphitron.rewrite.test.services;

/**
 * The value {@link FilmVerdictService#verdict} hands back for a film. Its errors arrive on the
 * outcome the generated fetcher returns, never in here, so it carries no errors component.
 */
public record FilmVerdict(String verdict) {}
