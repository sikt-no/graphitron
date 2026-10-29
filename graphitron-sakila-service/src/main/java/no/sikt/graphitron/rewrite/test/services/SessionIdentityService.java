package no.sikt.graphitron.rewrite.test.services;

import no.sikt.graphitron.rewrite.test.jooq.public_.tables.records.FilmRecord;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionHandleRecord;
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
     * The {@code $session.sessionNo} member witness: the parameter's boxed {@code Integer} is the
     * member's own type, so {@code argMapping: "number: $session.sessionNo"} binds it without the
     * service taking the whole handle.
     */
    public static Integer sessionNumber(DSLContext dsl, Integer number) {
        return number;
    }

    /**
     * The per-key {@code $session.sessionNo} member witness for the multi-tenant fixture: the
     * batched child service of {@link #mountedPrincipal}, bound to one member of the handle, so
     * under fan-out each tenant's batch reads its own connection's mounted session number.
     */
    public static Map<FilmRecord, Integer> mountedSessionNumber(
            Set<FilmRecord> films, DSLContext dsl, Integer number) {
        return films.stream().collect(Collectors.toMap(film -> film, film -> number));
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
