package no.sikt.graphitron.rewrite.test.querydb;

import org.jooq.DSLContext;

/**
 * Installs the session-identity fixture objects (claims and handle composite types, the
 * handle sequence, and the connect/disconnect routines) into a per-tenant test database.
 * The multi-tenant fixture package is generated with {@code <sessionState>} configured, so
 * every tenant acquisition mounts before the test's own work runs; a tenant database
 * created bare in a {@code @BeforeAll} therefore needs these objects present, mirroring
 * the definitions the main database gets from the sakila init script.
 */
final class TenantSessionFixture {

    private TenantSessionFixture() {}

    static void installSessionObjects(DSLContext tenant) {
        tenant.execute("create type session_claims as (sub text, tenant text)");
        tenant.execute("create type session_handle as (principal text, session_no integer)");
        tenant.execute("create sequence session_handle_seq");
        tenant.execute("""
            create function session_connect(p_claims session_claims) returns session_handle
            language plpgsql volatile
            as $$
            declare
                h session_handle;
            begin
                if (p_claims).sub = 'reject-me' then
                    raise exception 'unentitled principal: %', (p_claims).sub;
                end if;
                perform set_config('app.user_id', coalesce((p_claims).sub, ''), false);
                h.principal  := (p_claims).sub;
                h.session_no := nextval('session_handle_seq');
                return h;
            end;
            $$""");
        tenant.execute("""
            create function session_disconnect(p_handle session_handle) returns void
            language plpgsql volatile
            as $$
            begin
                perform set_config('app.user_id', '', false);
            end;
            $$""");
    }
}
