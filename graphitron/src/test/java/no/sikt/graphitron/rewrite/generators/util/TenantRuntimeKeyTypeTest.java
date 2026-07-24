package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionStateConfig;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the tenant-key typing of the generated runtime surfaces: with a configured
 * {@code <tenantColumn>} the catalog-read tenant type replaces the erased {@code Object} on
 * every tenant-keyed signature (constructor map, keyed acquisition, per-operation carrier), so
 * a consumer wiring a map keyed with the wrong type is a compile error rather than a
 * first-request lookup miss. Without the element the shipped {@code Object} shape is
 * unchanged.
 */
@UnitTier
class TenantRuntimeKeyTypeTest {

    private static String render(List<TypeSpec> units, String className) {
        return units.stream()
            .filter(t -> className.equals(t.name()))
            .findFirst()
            .orElseThrow()
            .toString();
    }

    @Test
    void configuredTenantColumnTypesEveryTenantKeyedSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none(), ClassName.get(Integer.class));

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .contains("java.util.Map<java.lang.Integer, javax.sql.DataSource> dataSourcesByTenant")
            .contains("java.util.Map<? extends java.lang.Integer, javax.sql.DataSource> dataSourcesByTenant")
            .contains("acquireForTenant(java.lang.Integer tenantKey");

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .contains("java.util.Map<java.lang.Integer,")
            .contains("dslFor(java.lang.Integer tenantKey");
    }

    @Test
    void multiTenantCarrierShipsTheRoutingStatics() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none(), ClassName.get(Integer.class));

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // Carrier resolution off the GraphQL context, failing loudly outside owned acquisition.
            .contains("static fake.code.generated.schema.TenantConnections of(")
            .contains("graphql.schema.DataFetchingEnvironment env")
            // The divined-key fold: typed return, collection flattening, agreement guard.
            .contains("static java.lang.Integer divinedTenant(java.lang.Object... candidates)")
            .contains("Tenant bindings disagree within one operation")
            .contains("The tenant binding value is absent")
            .contains("if (key instanceof java.lang.Integer typed)")
            // The build-time-path nested slot read.
            .contains("static java.lang.Object tenantSlot(java.lang.Object container, java.lang.String... path)")
            // The single loader-naming seam: bare path form plus the tenant-partitioned form
            // whose opaque segment keeps inherited-tenant batches tenant-homogeneous.
            .contains("static java.lang.String loaderName(")
            .contains("static java.lang.String tenantLoaderName(")
            .contains("loaderName(env) + \" tenant:\"");
    }

    @Test
    void singleTenantCarrierOmitsTheRoutingStatics() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none());

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .doesNotContain("divinedTenant")
            .doesNotContain("tenantSlot")
            .doesNotContain("DataFetchingEnvironment")
            // The default-source arm is multi-tenant machinery: absent the element, absent the
            // surface (no dead dslDefault/defaultPinned in single-tenant builds).
            .doesNotContain("dslDefault")
            .doesNotContain("defaultPinned");
    }

    @Test
    void multiTenantRuntimeShipsTheFanOutConfigurationSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none(), ClassName.get(Integer.class));

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            // Two flat scalars with named defaults, no builder or config record.
            .contains("int DEFAULT_FAN_OUT_CONCURRENCY = 8")
            .contains("java.time.Duration DEFAULT_FAN_OUT_TIMEOUT = java.time.Duration.ofSeconds(10)")
            // The executor-form canonical (consumer owns the concurrency bound) and the int-cap
            // overload (runtime owns a bounded platform-thread pool).
            .contains("java.util.concurrent.Executor fanOutExecutor,")
            .contains("int fanOutConcurrency,")
            .contains("boundedFanOutPool(fanOutConcurrency)")
            .contains("thread.setDaemon(true)")
            // The accessors the carrier's scatter join reads.
            .contains("java.util.concurrent.Executor fanOutExecutor()")
            .contains("java.time.Duration fanOutTimeout()");
    }

    @Test
    void multiTenantCarrierShipsTheScatterSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none(), ClassName.get(Integer.class));

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // The scatter method: typed keys, per-tenant unit of work, outcomes in key order.
            .contains("scatter(")
            .contains("java.util.Collection<java.lang.Integer> keys")
            .contains("java.util.function.Function<org.jooq.DSLContext, R> perTenant")
            // The outcome taxonomy: sealed, one arm per way a tenant's work can end.
            .contains("sealed interface Outcome<R>")
            .contains("final class Success<R> implements")
            .contains("final class Failed<R> implements")
            .contains("final class TimedOut<R> implements")
            // Concurrent pinning with per-key single acquisition, and the straggler quarantine.
            .contains("java.util.concurrent.ConcurrentHashMap<>()")
            .contains("pinnedByTenant.computeIfAbsent(")
            .contains("timedOutTenants")
            // The re-entrancy guard fails loudly instead of deadlocking the bounded pool.
            .contains("scatter is not re-entrant");

        var pinned = render(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        assertThat(pinned)
            .as("the straggler abort seam: evict without the disconnect hook")
            .contains("synchronized void abort()");
    }

    @Test
    void multiTenantCarrierShipsTheFanOutHelpers() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none(), ClassName.get(Integer.class));

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // The graphitron-owned request key the factory writes and fanOutDomain reads.
            .contains("String FAN_OUT_TENANTS_KEY = \"no.sikt.graphitron.request.fanOutTenants\"")
            // Domain: map-order intersection; named-but-unhosted is a pre-SQL request error.
            .contains("static java.util.List<java.lang.Integer> fanOutDomain(")
            .contains("hosted.contains(claimed)")
            // Union with per-element tenant stamping, failures appended after successful rows.
            .contains("static <R> java.util.List<java.lang.Object> fanOutRows(")
            .contains(".localContext(success.key())")
            // The batched sibling: per-key merge across tenants, one scatter per parent batch.
            .contains("fanOutBatchRows(")
            // The collapse: null element + path-bearing redacted error with typed classification.
            .contains("static graphql.execution.DataFetcherResult<java.util.List<java.lang.Object>> collapseFanOut(")
            .contains(".path(env.getExecutionStepInfo().getPath().segment(elements.size()))")
            .contains("\"classification\", failure.classification()")
            .contains("class FanOutFailure");

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .as("the domain reads the configured keys in map order off the runtime")
            .contains("java.util.Set<java.lang.Integer> tenantKeys()");
    }

    @Test
    void singleTenantOmitsTheFanOutSubstrate() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none());

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .doesNotContain("fanOut")
            .doesNotContain("FAN_OUT")
            .doesNotContain("tenantKeys")
            .doesNotContain("java.time.Duration");

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .doesNotContain("scatter")
            .doesNotContain("Outcome")
            .doesNotContain("ConcurrentHashMap")
            .doesNotContain("timedOutTenants");

        var pinned = render(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        assertThat(pinned).doesNotContain("synchronized void abort()");
    }

    @Test
    void singleTenantKeepsTheErasedObjectShape() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionStateConfig.none());

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .contains("java.util.Map<java.lang.Object, javax.sql.DataSource> dataSourcesByTenant")
            .contains("acquireForTenant(java.lang.Object tenantKey");

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier).contains("dslFor(java.lang.Object tenantKey");
    }
}
