package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.udt.records.SessionHandleRecord;
import org.jooq.DSLContext;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * The payload/contextArgument unification witness: {@code claims} names the mount method's
     * own payload parameter, so declaring it as a {@code contextArguments} entry at the
     * {@code @service} site adds no factory slot; the caller supplies the value once and this
     * method receives exactly what the mount was called with.
     */
    public static String mountedClaimsEcho(DSLContext dsl, String claims) {
        return claims;
    }

    /**
     * The per-key {@code $session} witness for the multi-tenant fixture: a batched child
     * service on the tenant-scoped {@code Film} type, so under fan-out each tenant's batch
     * runs on that tenant's own pinned connection and {@code identity} is the handle that
     * connection's mount returned. Every film in one batch reports the same handle; films from
     * different tenants report different ones.
     */
    public static Map<FilmRecord, String> mountedPrincipal(
            Set<FilmRecord> films, DSLContext dsl, SessionHandleRecord identity) {
        String principal = sessionPrincipal(dsl, identity);
        return films.stream().collect(Collectors.toMap(film -> film, film -> principal));
    }
}
