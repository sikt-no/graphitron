package no.sikt.graphitron.rewrite.test.services;

/**
 * Methods an {@code argMapping} entry names parameters of: {@link #compute} with two named
 * parameters, and {@link #pick} overloaded so that only the two overloads together carry both names.
 * A schema names a method by name alone, so an editor offering parameter names offers every
 * overload's.
 */
public final class ArgMappedService {

    private ArgMappedService() {}

    public static Object compute(Object film, Integer limit) {
        return film;
    }

    public static Object pick(Object film) {
        return film;
    }

    public static Object pick(Object film, Integer limit) {
        return film;
    }
}
