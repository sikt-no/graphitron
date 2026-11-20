package no.sikt.graphitron.rewrite.test.services;

/**
 * A class whose {@code getDetail()} narrows the {@code Object} {@link BlurbDetailSource} declares to a
 * {@link FilmBlurb}. The compiler writes a bridge {@code getDetail()} returning {@code Object} beside
 * it, which is synthetic, so the class offers its detail once, as the {@code FilmBlurb}.
 */
public final class CovariantBlurbHolder implements BlurbDetailSource {

    @Override
    public FilmBlurb getDetail() {
        return new FilmBlurb("covariant");
    }
}
