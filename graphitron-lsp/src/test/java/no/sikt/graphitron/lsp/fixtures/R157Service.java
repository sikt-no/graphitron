package no.sikt.graphitron.lsp.fixtures;

/**
 * Test producer for {@code R157PipelineTest}. Reflection-only record binding means a type
 * acquires its backing class by being the reflected return type of a producer field; the
 * deprecated {@code @record} directive no longer binds. These methods let the test SDL bind
 * {@code FilmCard} / {@code FilmPojoView} to {@link R157FilmRecord} / {@link R157FilmPojo} through
 * a real {@code @service} producer. Bodies never run, only the declared return type is reflected.
 */
public final class R157Service {

    private R157Service() {}

    public static R157FilmRecord makeFilmRecord() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }

    public static R157FilmPojo makeFilmPojo() {
        throw new UnsupportedOperationException("codegen-time return-type stub");
    }
}
