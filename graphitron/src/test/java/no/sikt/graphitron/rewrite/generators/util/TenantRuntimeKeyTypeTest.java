package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.FieldSpec;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.session.SessionHooks;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
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

    private static TypeSpec type(List<TypeSpec> units, String className) {
        return units.stream()
            .filter(t -> className.equals(t.name()))
            .findFirst()
            .orElseThrow();
    }

    private static String render(List<TypeSpec> units, String className) {
        return type(units, className).toString();
    }

    private static MethodSpec method(TypeSpec type, String name) {
        return type.methodSpecs().stream()
            .filter(m -> name.equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method " + name + " on " + type.name()));
    }

    private static List<String> parameters(MethodSpec method) {
        return method.parameters().stream()
            .map(p -> p.type() + " " + p.name())
            .toList();
    }

    @Test
    void configuredTenantColumnTypesEveryTenantKeyedSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .contains("Source> sourcesByTenant")
            .contains("java.util.Map<? extends java.lang.Integer, javax.sql.DataSource> dataSourcesByTenant")
            .contains("acquireForTenant(java.lang.Integer tenantKey");

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .contains("java.util.Optional<java.lang.Integer>")
            .contains("dslFor(java.lang.Integer tenantKey");
    }

    @Test
    void multiTenantAcquisitionTakesTheTenantItMountsFor() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

        // The tenant rides between the seam's settings and the abort executor; the runtime's
        // Optional.of(key) / Optional.empty() arguments are pinned where they run, in
        // TenantAuthorizationSubstrateTest.
        assertThat(parameters(method(type(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME), "acquire")))
            .containsExactly(
                "javax.sql.DataSource dataSource",
                "org.jooq.SQLDialect dialect",
                "org.jooq.conf.Settings settings",
                "java.util.Optional<java.lang.Integer> mountedTenant",
                "java.util.concurrent.Executor abortExecutor");
    }

    @Test
    void singleTenantAcquisitionCarriesNoTenant() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);

        assertThat(parameters(method(type(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME), "acquire")))
            .containsExactly(
                "javax.sql.DataSource dataSource",
                "org.jooq.SQLDialect dialect",
                "org.jooq.conf.Settings settings",
                "java.util.concurrent.Executor abortExecutor");
    }

    @Test
    void multiTenantCarrierHoldsTheRequestTenantSet() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));
        var carrier = type(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);

        // The shape of the set the carrier owns. What entryFor does with it (refusal before any
        // getConnection and ahead of the hosting check) is pinned where it runs, in
        // TenantAuthorizationSubstrateTest and TenantDivinedRoutingExecutionTest.
        var tenants = carrier.fieldSpecs().stream()
            .filter(f -> "tenants".equals(f.name()))
            .findFirst()
            .orElseThrow();
        assertThat(tenants.type().toString()).isEqualTo("java.util.Set<java.lang.Integer>");
        assertThat(tenants.modifiers()).containsExactlyInAnyOrder(Modifier.PRIVATE, Modifier.FINAL);

        var constructor = carrier.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst()
            .orElseThrow();
        assertThat(parameters(constructor)).containsExactly(
            "fake.code.generated.schema.GraphitronRuntime runtime",
            "fake.code.generated.schema.GraphitronTransactionProvider.CommitPolicy commitPolicy",
            "fake.code.generated.schema.RequestTenants requestTenants");

        // The read side for the per-row lookups' null-not-error skip.
        var permits = method(carrier, "permits");
        assertThat(permits.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(permits.returnType()).isEqualTo(TypeName.BOOLEAN);
        assertThat(parameters(permits)).containsExactly(
            "graphql.schema.DataFetchingEnvironment env",
            "java.lang.Integer tenantKey");
    }

    @Test
    void multiTenantBuildEmitsTheRequestTenancyValue() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));
        var requestTenants = type(units, ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);

        // Record-shaped: the two components, the canonical constructor over them, and accessors.
        // The membership refusal is pinned where it runs, in TenantAuthorizationSubstrateTest.
        assertThat(requestTenants.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(requestTenants.fieldSpecs())
            .extracting(f -> f.type() + " " + f.name())
            .containsExactly(
                "java.util.Set<java.lang.Integer> tenants",
                "java.util.Optional<java.lang.Integer> defaultTenant");
        var constructor = requestTenants.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst()
            .orElseThrow();
        assertThat(constructor.modifiers()).contains(Modifier.PUBLIC);
        assertThat(parameters(constructor)).containsExactly(
            "java.util.Set<java.lang.Integer> tenants",
            "java.util.Optional<java.lang.Integer> defaultTenant");
        assertThat(method(requestTenants, "tenants").returnType().toString())
            .isEqualTo("java.util.Set<java.lang.Integer>");
        assertThat(method(requestTenants, "defaultTenant").returnType().toString())
            .isEqualTo("java.util.Optional<java.lang.Integer>");

        var of = method(requestTenants, "of");
        assertThat(of.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(parameters(of)).containsExactly("java.util.Collection<java.lang.Integer> tenants");
        var withDefault = method(requestTenants, "withDefault");
        assertThat(withDefault.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(parameters(withDefault)).containsExactly(
            "java.util.Collection<java.lang.Integer> tenants",
            "java.lang.Integer defaultTenant");
    }

    @Test
    void multiTenantCarrierShipsTheGlobalReadAcquisition() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));
        var carrier = type(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);

        var defaultTenant = carrier.fieldSpecs().stream()
            .filter(f -> "defaultTenant".equals(f.name()))
            .findFirst()
            .orElseThrow();
        assertThat(defaultTenant.type().toString()).isEqualTo("java.util.Optional<java.lang.Integer>");

        var globals = carrier.methodSpecs().stream().filter(m -> "dslGlobal".equals(m.name())).toList();
        assertThat(globals).hasSize(2);
        var instance = globals.stream().filter(m -> !m.modifiers().contains(Modifier.STATIC)).findFirst().orElseThrow();
        assertThat(instance.modifiers()).contains(Modifier.PUBLIC);
        assertThat(parameters(instance)).isEmpty();
        assertThat(instance.returnType().toString()).isEqualTo("org.jooq.DSLContext");
        var statik = globals.stream().filter(m -> m.modifiers().contains(Modifier.STATIC)).findFirst().orElseThrow();
        assertThat(statik.modifiers()).contains(Modifier.PUBLIC);
        assertThat(parameters(statik)).containsExactly("graphql.schema.DataFetchingEnvironment env");
        assertThat(statik.returnType().toString()).isEqualTo("org.jooq.DSLContext");
    }

    @Test
    void singleTenantBuildEmitsNoRequestTenancyValueAndNoGlobalReadAcquisition() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);
        var carrier = type(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);

        assertThat(units).extracting(TypeSpec::name)
            .doesNotContain(ConnectionRuntimeClassGenerator.REQUEST_TENANTS_CLASS_NAME);
        assertThat(carrier.fieldSpecs()).extracting(FieldSpec::name).doesNotContain("defaultTenant");
        assertThat(carrier.methodSpecs()).extracting(MethodSpec::name).doesNotContain("dslGlobal");
    }

    @Test
    void singleTenantCarrierHoldsNoTenantSet() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);
        var carrier = type(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);

        assertThat(carrier.fieldSpecs()).extracting(FieldSpec::name).doesNotContain("tenants");
        assertThat(carrier.methodSpecs()).extracting(MethodSpec::name).doesNotContain("permits");
    }

    @Test
    void multiTenantCarrierShipsTheRoutingStatics() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

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
    void singleTenantCarrierOmitsTheRoutingMachinery_butKeepsTheUnifiedResolution() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // Genuinely <tenantColumn>-only machinery stays absent in single-tenant builds.
            .doesNotContain("divinedTenant")
            .doesNotContain("tenantSlot")
            .doesNotContain("scatter")
            .doesNotContain("timedOutTenants")
            // The unified per-key carrier is both topologies' one acquisition seam: the
            // single-tenant path resolves contexts through of(env) + dslDefault() (the one-key
            // case), and the untenanted slot is the map's Optional.empty() entry, never a field.
            .contains("static fake.code.generated.schema.TenantConnections of(")
            .contains("dslDefault")
            .doesNotContain("defaultPinned");
    }

    @Test
    void multiTenantRuntimeShipsTheFanOutConfigurationSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

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
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

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
            .contains("entries.computeIfAbsent(")
            .contains("timedOutTenants")
            // The re-entrancy guard fails loudly instead of deadlocking the bounded pool.
            .contains("scatter is not re-entrant");

        var pinned = render(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        assertThat(pinned)
            .as("the straggler abort seam: evict without the unmount method")
            .contains("synchronized void abort()");
    }

    @Test
    void multiTenantCarrierShipsTheFanOutHelpers() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE, ClassName.get(Integer.class));

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // The graphitron-owned request key the factory writes and the instrumentation reads.
            .contains("String TENANTS_KEY = \"no.sikt.graphitron.request.tenants\"")
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
            // The wire vocabulary is single-sourced as named constants on the marker.
            .contains("String FAILED = \"TenantFanOutFailed\"")
            .contains("String TIMED_OUT = \"TenantFanOutTimedOut\"")
            .contains("class FanOutFailure");

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .as("the domain reads the configured keys in map order off the runtime")
            .contains("java.util.Set<java.lang.Integer> tenantKeys()");
    }

    @Test
    void singleTenantOmitsTheFanOutSubstrate() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .doesNotContain("fanOut")
            .doesNotContain("FAN_OUT")
            .doesNotContain("tenantKeys")
            .doesNotContain("java.time.Duration");

        // The concurrent map itself is not fan-out substrate any more: the unified carrier uses
        // one ConcurrentHashMap in both topologies (computeIfAbsent is the only minting
        // mechanism), so only the genuinely <tenantColumn>-gated machinery is asserted absent.
        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .doesNotContain("scatter")
            .doesNotContain("Outcome")
            .doesNotContain("timedOutTenants");

        var pinned = render(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        assertThat(pinned).doesNotContain("synchronized void abort()");
    }

    @Test
    void singleTenantKeepsTheErasedObjectShape() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionHooks.NotConfigured.INSTANCE);

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .contains("Source> sourcesByTenant")
            .contains("java.util.Map<?, javax.sql.DataSource> dataSourcesByTenant")
            .contains("acquireForTenant(java.lang.Object tenantKey");

        var carrier = render(units, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(carrier).contains("dslFor(java.lang.Object tenantKey");
    }
}
