package no.sikt.graphitron.rewrite.test.services;

/**
 * A POJO carrying {@link FilmBlurb}, returned by {@link FilmBlurbHolderService}. Binds
 * {@code FilmBlurbHolder} as a {@code PojoResultType}; its {@code blurb()} accessor grounds
 * {@code FilmBlurb} class-backed through the parent-accessor chain, so {@code FilmBlurb} is reached
 * both this way and as a nesting projection of {@code @table Film} (the mixed-source reach).
 */
public record FilmBlurbHolder(FilmBlurb blurb) {}
