package no.sikt.graphitron.rewrite.session;

import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Hand-built {@link SessionIdentityRows} for tiers that read no store: the same values
 * {@code SessionIdentityRows.read} yields, assembled directly so unit and pipeline fixtures need
 * no capture and no consumer classes on the classpath.
 */
public final class SessionIdentityFixtures {

    private SessionIdentityFixtures() {}

    /** Identity supplied per request, no handle named. */
    public static SessionIdentityRows handleLess() {
        return SessionIdentityRows.handleLess();
    }

    /** Identity supplied per request, with a handle of {@code handleType} and the given members. */
    public static SessionIdentityRows handled(TypeName handleType, SessionIdentityRows.Member... members) {
        return SessionIdentityRows.handled(new SessionIdentityRows.Handle(handleType, List.of(members)));
    }

    /** A handle member read through a {@code get} accessor. */
    public static SessionIdentityRows.Member member(String name, TypeName type) {
        return new SessionIdentityRows.Member(name,
            "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1), type);
    }

    /**
     * The identity the emitted-code harness tests mount through: a {@code String} handle, which the
     * recording session state returns from its mount and the carrier reads back.
     */
    public static SessionIdentityRows recording() {
        return handled(ClassName.get(String.class));
    }
}
