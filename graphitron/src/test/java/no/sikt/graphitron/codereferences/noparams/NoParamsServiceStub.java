package no.sikt.graphitron.codereferences.noparams;

/**
 * Name-less-POJO service stub. Lives in the {@code -parameters}-free {@code noparams} package
 * alongside {@link NamelessErrorsPayload} so the payload's backing class binds through a real
 * {@code @service} producer without dragging a compile-time reference into the {@code -parameters}
 * test tree (the SDL fixture names this class only by string).
 */
public final class NoParamsServiceStub {

    private NoParamsServiceStub() {}

    /** Grounds an SDL payload type to {@link NamelessErrorsPayload}. */
    public static NamelessErrorsPayload runNameless() {
        throw new UnsupportedOperationException();
    }
}
