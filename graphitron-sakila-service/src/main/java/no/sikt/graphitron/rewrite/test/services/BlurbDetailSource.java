package no.sikt.graphitron.rewrite.test.services;

/** Declares a detail of any class, which {@link CovariantBlurbHolder} narrows to a {@link FilmBlurb}. */
public interface BlurbDetailSource {

    Object getDetail();
}
