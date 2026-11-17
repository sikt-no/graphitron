package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.jooq.public_.Routines;
import no.sikt.graphitron.rewrite.test.jooq.public_.udt.records.SessionClaimsRecord;
import no.sikt.graphitron.rewrite.test.services.OrgTenantSessionIdentity;
import no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity;
import no.sikt.graphitron.rewrite.test.services.SakilaTenantSessionIdentity;
import org.jooq.Configuration;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The connection sources the execution tests hand each request, one per generated package, each
 * built with that package's {@code ConnectionSources.mounting}: callbacks over the facades in the
 * sakila services module, with the request's claims captured when the input is built. A tenant
 * package takes the default pool (for the key without a tenant), a pool per tenant and the tenants
 * the request may reach, which are the source's {@code tenants()}, its fan-out domain, in the order
 * given; a tenant outside them is refused with that package's {@code KeyNotPermitted} reading
 * {@value #NOT_PERMITTED_PREFIX}..., the way a consumer's source refuses a tenant the caller's claims
 * do not reach, and a tenant the map does not name, or the default key when no default pool is
 * given, is refused naming the missing pool. The main package's is {@code SakilaSessions}.
 */
final class Sources {

    private Sources() {}

    /** The start of the refusal a tenant outside the request's tenants gets. */
    static final String NOT_PERMITTED_PREFIX = "Tenant '";

    /** The refusal a tenant outside the request's tenants gets. */
    static String notPermitted(Object tenant) {
        return NOT_PERMITTED_PREFIX + tenant + "' is not permitted for this request.";
    }

    /** The main package: {@code SakilaSessions}, every key on {@code pool}. */
    static no.sikt.graphitron.generated.schema.ConnectionSource main(String claims, DataSource pool) {
        return no.sikt.graphitron.sakila.example.app.SakilaSessions.of(claims, pool);
    }

    /**
     * The main package over a pool per tenant key, mounting as {@code SakilaSessions} does: for the
     * tests that drive the carrier's keyed acquisition directly.
     */
    static no.sikt.graphitron.generated.schema.ConnectionSource main(
            String claims, DataSource defaultPool, Map<Object, DataSource> tenantPools) {
        return no.sikt.graphitron.generated.schema.ConnectionSources.mounting(
            key -> pool(key.tenant(), defaultPool, tenantPools,
                no.sikt.graphitron.generated.schema.KeyNotPermitted::new),
            (connection, key) -> SakilaSessionIdentity.mount(configuration(connection), claims),
            (connection, key, handle) -> SakilaSessionIdentity.unmount(configuration(connection), handle));
    }

    /** The {@code federated} package: the default identity, over the same facade. */
    static no.sikt.graphitron.generated.federated.schema.ConnectionSource federated(String claims, DataSource pool) {
        return no.sikt.graphitron.generated.federated.schema.ConnectionSources.mounting(
            key -> pool,
            (connection, key) -> SakilaSessionIdentity.mount(configuration(connection), claims),
            (connection, key, handle) -> SakilaSessionIdentity.unmount(configuration(connection), handle));
    }

    /** The {@code issuing} package: the default identity, over the same facade. */
    static no.sikt.graphitron.generated.issuing.schema.ConnectionSource issuing(String claims, DataSource pool) {
        return no.sikt.graphitron.generated.issuing.schema.ConnectionSources.mounting(
            key -> pool,
            (connection, key) -> SakilaSessionIdentity.mount(configuration(connection), claims),
            (connection, key, handle) -> SakilaSessionIdentity.unmount(configuration(connection), handle));
    }

    /**
     * The {@code multischemamutation} package: a mount over the jOOQ-generated routines directly,
     * with the routine's own composite payload and no hand-written facade between.
     */
    static no.sikt.graphitron.generated.multischemamutation.schema.ConnectionSource multischemaMutation(
            String principal, DataSource pool) {
        return no.sikt.graphitron.generated.multischemamutation.schema.ConnectionSources.mounting(
            key -> pool,
            (connection, key) -> Routines.sessionConnect(configuration(connection), new SessionClaimsRecord(principal, null)),
            (connection, key, handle) -> Routines.sessionDisconnect(configuration(connection), handle));
    }

    /**
     * The {@code multitenant} package: the tenant the connection is mounted for rides the key. The
     * request may reach every tenant {@code tenantPools} hosts, in ascending order.
     */
    static no.sikt.graphitron.generated.multitenant.schema.ConnectionSource multitenant(
            String claims, DataSource defaultPool, Map<Integer, DataSource> tenantPools) {
        return multitenant(claims, defaultPool, tenantPools, new java.util.TreeSet<>(tenantPools.keySet()));
    }

    /** As {@link #multitenant(String, DataSource, Map)}, the request reaching {@code tenants} only. */
    static no.sikt.graphitron.generated.multitenant.schema.ConnectionSource multitenant(
            String claims, DataSource defaultPool, Map<Integer, DataSource> tenantPools,
            Collection<Integer> tenants) {
        return no.sikt.graphitron.generated.multitenant.schema.ConnectionSources.mounting(
            key -> permittedPool(key.tenant(), tenants, defaultPool, tenantPools,
                no.sikt.graphitron.generated.multitenant.schema.KeyNotPermitted::new),
            tenants,
            (connection, key) -> SakilaTenantSessionIdentity.mount(configuration(connection), key.tenant(), claims),
            (connection, key, handle) -> SakilaTenantSessionIdentity.unmount(configuration(connection), handle));
    }

    /**
     * The {@code convertedtenant} package: a handle-less mount that records the tenant in its column
     * form and sets no database state, so its unmount has nothing to clear. {@code tenants} is the
     * fan-out domain only: a tenant the pools host is served.
     */
    static no.sikt.graphitron.generated.convertedtenant.schema.ConnectionSource convertedTenant(
            String claims, DataSource defaultPool, Map<String, DataSource> tenantPools, Collection<String> tenants) {
        // The fan-out domain may be spelled any way the column stores as one value; the keys the
        // source is asked for are in the column's form, so it refuses by its pools alone.
        return no.sikt.graphitron.generated.convertedtenant.schema.ConnectionSources.mounting(
            key -> pool(key.tenant(), defaultPool, tenantPools,
                no.sikt.graphitron.generated.convertedtenant.schema.KeyNotPermitted::new),
            tenants,
            (connection, key) -> OrgTenantSessionIdentity.mount(configuration(connection), key.tenant(), claims),
            (connection, key) -> {});
    }

    /**
     * {@link #pool}, after refusing a tenant outside {@code tenants} with {@link #notPermitted}. The
     * key's tenant is in the tenant column's canonical form, so {@code tenants} must be too.
     */
    static <T> DataSource permittedPool(Optional<T> tenant, Collection<T> tenants, DataSource defaultPool,
                                        Map<T, DataSource> tenantPools,
                                        Function<String, ? extends RuntimeException> refusal) {
        if (tenant.isPresent() && !tenants.contains(tenant.get())) {
            throw refusal.apply(notPermitted(tenant.get()));
        }
        return pool(tenant, defaultPool, tenantPools, refusal);
    }

    /** The pool {@code tenant} names, or the default for no tenant; refused with {@code refusal} when absent. */
    static <T> DataSource pool(Optional<T> tenant, DataSource defaultPool, Map<T, DataSource> tenantPools,
                               Function<String, ? extends RuntimeException> refusal) {
        if (tenant.isEmpty()) {
            if (defaultPool == null) {
                throw refusal.apply("No pool hosts the default connection.");
            }
            return defaultPool;
        }
        DataSource pool = tenantPools.get(tenant.get());
        if (pool == null) {
            throw refusal.apply("No pool hosts tenant '" + tenant.get() + "'.");
        }
        return pool;
    }

    static Configuration configuration(Connection connection) {
        return DSL.using(connection, SQLDialect.POSTGRES).configuration();
    }
}
