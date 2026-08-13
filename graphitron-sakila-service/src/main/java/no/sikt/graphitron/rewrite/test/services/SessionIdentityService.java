package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionHandleRecord;
import org.jooq.DSLContext;

/**
 * The {@code $session} sigil's consuming side: a service method whose {@code identity}
 * parameter binds to the session handle the {@code <sessionState>} mount returned, via
 * {@code argMapping: "identity: $session"}. The handle is the mount's own resolved identity
 * (the database-side facts service code would otherwise re-derive from the raw claims), read
 * per pinned connection off the resolved {@code DSLContext}'s configuration.
 */
public final class SessionIdentityService {

    private SessionIdentityService() {}

    /** The mounted principal as the mount resolved it, with the per-mount session number. */
    public static String sessionPrincipal(DSLContext dsl, SessionHandleRecord identity) {
        if (identity == null) {
            return null;
        }
        return identity.getPrincipal() + "#" + identity.getSessionNo();
    }
}
