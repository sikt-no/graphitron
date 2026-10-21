package no.sikt.graphitron.codereferences.noparams;

/**
 * Name-less service stub. Lives in the {@code -parameters}-free {@code noparams} package so its
 * methods' parameters carry no compiled names, without dragging a compile-time reference into the
 * {@code -parameters} test tree (the SDL fixture names this class only by string).
 */
public final class NoParamsServiceStub {

    private NoParamsServiceStub() {}

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
