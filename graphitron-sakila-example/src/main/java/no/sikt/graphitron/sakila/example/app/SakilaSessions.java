package no.sikt.graphitron.sakila.example.app;

import no.sikt.graphitron.generated.schema.SessionState;
import no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity;

/**
 * The example's session identity as code: a {@link SessionState} whose callbacks mount the
 * request's identity through the {@link SakilaSessionIdentity} facade (one raw claims JSON string,
 * reshaped into the composite the {@code session_connect} routine takes) and unmount through the
 * paired routine. The claims are captured here, when the input is built, so the mount needs no
 * ambient state on whatever thread first needs a connection.
 */
public final class SakilaSessions {

    private SakilaSessions() {}

    /** The session state for a request carrying {@code claims}. */
    public static SessionState of(String claims) {
        return SessionState.of(
            session -> SakilaSessionIdentity.mount(session.configuration(), claims),
            (session, handle) -> SakilaSessionIdentity.unmount(session.configuration(), handle));
    }
}
