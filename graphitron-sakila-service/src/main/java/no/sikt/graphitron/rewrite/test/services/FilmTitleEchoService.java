package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.conditions.FilmTitleFilter;

/**
 * A {@code @service} taking the bean a {@code @condition} on the same parent also takes, so the
 * generated fetchers class and conditions class each host a builder for one bean class side by side.
 */
public final class FilmTitleEchoService {

    private FilmTitleEchoService() {}

    /** The filter's title, as the service received it. */
    public static String echo(FilmTitleFilter by) {
        return by == null ? null : by.title();
    }
}
