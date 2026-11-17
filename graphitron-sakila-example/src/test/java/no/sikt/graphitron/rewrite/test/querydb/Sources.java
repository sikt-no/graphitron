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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The connection sources the execution tests hand each request, one per generated package, each
 * built with that package's {@code ConnectionSources.mounting}: callbacks over the facades in the
 * sakila services module, with the request's claims captured when the input is built. A tenant
 * package takes the default pool (for the key without a tenant) and a pool per tenant; a tenant
 * the map does not name is refused with that package's {@code KeyNotPermitted}, as is the default
 * key when no default pool is given. The main package's is {@code SakilaSessions}.
 */
final class Sources {

    private Sources() {}

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

    /** The {@code multitenant} package: the tenant the connection is mounted for rides the key. */
    static no.sikt.graphitron.generated.multitenant.schema.ConnectionSource multitenant(
            String claims, DataSource defaultPool, Map<Integer, DataSource> tenantPools) {
        return no.sikt.graphitron.generated.multitenant.schema.ConnectionSources.mounting(
            key -> pool(key.tenant(), defaultPool, tenantPools,
                no.sikt.graphitron.generated.multitenant.schema.KeyNotPermitted::new),
            (connection, key) -> SakilaTenantSessionIdentity.mount(configuration(connection), key.tenant(), claims),
            (connection, key, handle) -> SakilaTenantSessionIdentity.unmount(configuration(connection), handle));
    }

    /**
     * The {@code convertedtenant} package: a handle-less mount that records the tenant in its column
     * form and sets no database state, so its unmount has nothing to clear.
     */
    static no.sikt.graphitron.generated.convertedtenant.schema.ConnectionSource convertedTenant(
            String claims, DataSource defaultPool, Map<String, DataSource> tenantPools) {
        return no.sikt.graphitron.generated.convertedtenant.schema.ConnectionSources.mounting(
            key -> pool(key.tenant(), defaultPool, tenantPools,
                no.sikt.graphitron.generated.convertedtenant.schema.KeyNotPermitted::new),
            (connection, key) -> OrgTenantSessionIdentity.mount(configuration(connection), key.tenant(), claims),
            (connection, key) -> {});
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
