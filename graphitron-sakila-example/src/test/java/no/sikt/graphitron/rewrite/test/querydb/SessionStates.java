package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.services.OrgTenantSessionIdentity;
import no.sikt.graphitron.rewrite.test.services.SakilaTenantSessionIdentity;

/**
 * Session identity as code for the tenant executions' generated packages, each of which has a
 * {@code SessionState} of its own: callbacks over the facades in the sakila services module, with
 * the request's claims captured when the input is built. The main package's is
 * {@code SakilaSessions}.
 */
final class SessionStates {

    private SessionStates() {}

    /** The {@code multitenant} package: the tenant the connection is mounted for rides the session. */
    static no.sikt.graphitron.generated.multitenant.schema.SessionState multitenant(String claims) {
        return no.sikt.graphitron.generated.multitenant.schema.SessionState.of(
            session -> SakilaTenantSessionIdentity.mount(session.configuration(), session.tenant(), claims),
            (session, handle) -> SakilaTenantSessionIdentity.unmount(session.configuration(), handle));
    }

    /** The {@code convertedtenant} package: a handle-less mount that records the tenant in its column form. */
    static no.sikt.graphitron.generated.convertedtenant.schema.SessionState convertedTenant(String claims) {
        return no.sikt.graphitron.generated.convertedtenant.schema.SessionState.of(
            session -> OrgTenantSessionIdentity.mount(session.configuration(), session.tenant(), claims));
    }
}
