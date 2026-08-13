package no.sikt.graphitron.codereferences.noparams;

/**
 * Name-less-POJO service stub. Lives in the {@code -parameters}-free {@code noparams} package
 * alongside {@link NamelessErrorsPayload} so the payload's backing class binds through a real
 * {@code @service} producer without dragging a compile-time reference into the {@code -parameters}
 * test tree (the SDL fixture names this class only by string).
 */
public final class NoParamsServiceStub {

    private NoParamsServiceStub() {}

    /**
     * Grounds an SDL payload type to {@link NamelessErrorsPayload}. Batch-shaped, like every child
     * {@code @service} signature; the keys parameter is nameless here too, which is immaterial to it
     * (a SOURCES parameter is claimed by its type's shape, never by its name).
     */
    public static java.util.Map<org.jooq.Row1<Integer>, NamelessErrorsPayload> runNameless(
            java.util.Set<org.jooq.Row1<Integer>> keys) {
        throw new UnsupportedOperationException();
    }

    /**
     * A {@code <mount>}-shaped method whose payload parameter has no compiled name: the
     * session-hook resolver requires real names on payload parameters (they become factory
     * contextArgument slots), so pointing {@code <mount>} here pins the
     * {@code ParameterNamesMissing} rejection.
     */
    public static void mountNameless(org.jooq.Configuration cfg, String claims) {
        throw new UnsupportedOperationException();
    }
}
