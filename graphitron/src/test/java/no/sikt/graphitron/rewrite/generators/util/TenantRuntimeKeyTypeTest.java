package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the tenant-key typing of the generated runtime surfaces: with a configured
 * {@code <tenantColumn>} the catalog-read tenant type replaces the erased {@code Object} on
 * every tenant-keyed signature (the {@code ConnectionKey} a source is asked with, the request
 * tenant set, the per-operation carrier), so a source reading a key's tenant as the wrong type is a
 * compile error rather than a first-request lookup miss. Without the element the shipped
 * {@code Object} shape is unchanged.
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
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

        var key = render(units, "ConnectionKey");
        assertThat(key)
            .contains("java.util.Optional<java.lang.Integer> tenant")
            .contains("forTenant(java.lang.Integer tenant)")
            .contains("java.lang.Integer tenantIn(java.lang.Object localContext)");

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .contains("java.util.List<java.lang.Integer> domain")
            .contains("dslFor(java.lang.Integer tenantKey");

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .as("the runtime holds no pool and no tenant map: a request's source supplies its connections")
            .doesNotContain("javax.sql.DataSource")
            .doesNotContain("acquireForTenant");
        assertThat(parameters(method(type(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME), "acquire")))
            .containsExactly(
                "fake.code.generated.schema.ConnectionKey key",
                "fake.code.generated.schema.ConnectionSource source");
    }

    @Test
    void multiTenantAcquisitionTakesTheSourceAndTheKeyItLeasesFor() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

        // The tenant rides in the key the source is asked with; what a source does with it is
        // pinned where it runs, in TenantAuthorizationSubstrateTest.
        assertThat(parameters(method(type(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME), "acquire")))
            .containsExactly(
                "fake.code.generated.schema.ConnectionSource source",
                "fake.code.generated.schema.ConnectionKey key",
                "org.jooq.SQLDialect dialect",
                "org.jooq.conf.Settings settings",
                "java.util.concurrent.Executor abortExecutor");
    }

    @Test
    void singleTenantAcquisitionHasTheSameKeyedShape() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());

        assertThat(parameters(method(type(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME), "acquire")))
            .containsExactly(
                "fake.code.generated.schema.ConnectionSource source",
                "fake.code.generated.schema.ConnectionKey key",
                "org.jooq.SQLDialect dialect",
                "org.jooq.conf.Settings settings",
                "java.util.concurrent.Executor abortExecutor");
    }

    @Test
    void multiTenantCarrierTakesOnlyTheSource_andAsksItForTheDomain() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);
        var carrier = type(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);

        // The constructor is the same in every build: which tenants a request may reach is the
        // source's to say. What the carrier does with a refusal and the domain is pinned where it
        // runs, in TenantAuthorizationSubstrateTest and ConnectionContractTest.
        var constructor = carrier.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst()
            .orElseThrow();
        assertThat(parameters(constructor)).containsExactly(
            "fake.code.generated.schema.GraphitronRuntime runtime",
            "fake.code.generated.schema.GraphitronTransactionProvider.CommitPolicy commitPolicy",
            "fake.code.generated.schema.ConnectionSource source");
        assertThat(carrier.fieldSpecs()).extracting(FieldSpec::name)
            .doesNotContain("tenants", "defaultTenant", "entityDefaultTenant", "TENANTS_KEY")
            .contains("refusedKeys", "domain");
        assertThat(carrier.fieldSpecs().stream().filter(f -> "domain".equals(f.name())).findFirst().orElseThrow()
            .type().toString()).isEqualTo("java.util.List<java.lang.Integer>");
        assertThat(carrier.methodSpecs()).extracting(MethodSpec::name)
            .doesNotContain("permits", "entityDefaultTenant", "dslGlobal");

        // The read side for the per-row lookups' null-not-error skip.
        var ifPermitted = method(carrier, "dslForIfPermitted");
        assertThat(ifPermitted.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(ifPermitted.returnType().toString()).isEqualTo("org.jooq.DSLContext");
        assertThat(parameters(ifPermitted)).containsExactly(
            "graphql.schema.DataFetchingEnvironment env",
            "java.lang.Integer tenantKey");
        assertThat(units).extracting(TypeSpec::name).doesNotContain("RequestTenants");
    }

    @Test
    void multiTenantSourceNamesItsDomain_andIsBuiltWithOf() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);
        var source = type(units, ConnectionRuntimeClassGenerator.CONNECTION_SOURCE_CLASS_NAME);

        assertThat(source.annotations()).isEmpty();
        var tenants = method(source, "tenants");
        assertThat(tenants.modifiers()).contains(Modifier.PUBLIC, Modifier.ABSTRACT);
        assertThat(tenants.returnType().toString()).isEqualTo("java.util.Set<java.lang.Integer>");
        var of = method(source, "of");
        assertThat(of.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(parameters(of)).containsExactly(
            "fake.code.generated.schema.ConnectionSource.Acquire acquire",
            "java.util.Collection<? extends java.lang.Integer> tenants");
        assertThat(source.typeSpecs()).extracting(TypeSpec::name).containsExactly("Acquire");

        var sources = type(units, ConnectionRuntimeClassGenerator.CONNECTION_SOURCES_CLASS_NAME);
        assertThat(parameters(method(sources, "of"))).containsExactly(
            "java.util.function.Function<fake.code.generated.schema.ConnectionKey, javax.sql.DataSource> pool",
            "java.util.Collection<? extends java.lang.Integer> tenants");
        assertThat(parameters(method(sources, "mounting"))).containsExactly(
            "java.util.function.Function<fake.code.generated.schema.ConnectionKey, javax.sql.DataSource> pool",
            "java.util.Collection<? extends java.lang.Integer> tenants",
            "fake.code.generated.schema.ConnectionSources.Mount mount",
            "fake.code.generated.schema.ConnectionSources.Unmount unmount");
    }

    @Test
    void singleTenantSourceStaysFunctional_withADefaultTenantsThatThrows() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());
        var source = type(units, ConnectionRuntimeClassGenerator.CONNECTION_SOURCE_CLASS_NAME);

        assertThat(source.annotations()).extracting(Object::toString)
            .containsExactly("@java.lang.FunctionalInterface");
        var tenants = method(source, "tenants");
        assertThat(tenants.modifiers()).contains(Modifier.PUBLIC, Modifier.DEFAULT);
        assertThat(tenants.returnType().toString()).isEqualTo("java.util.Set<java.lang.Object>");
        assertThat(source.methodSpecs()).extracting(MethodSpec::name).doesNotContain("of");

        var sources = type(units, ConnectionRuntimeClassGenerator.CONNECTION_SOURCES_CLASS_NAME);
        assertThat(parameters(method(sources, "of"))).containsExactly(
            "java.util.function.Function<fake.code.generated.schema.ConnectionKey, javax.sql.DataSource> pool");
    }

    @Test
    void singleTenantCarrierHoldsNoDomain() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());
        var carrier = type(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);

        assertThat(carrier.fieldSpecs()).extracting(FieldSpec::name).doesNotContain("domain");
        assertThat(carrier.methodSpecs()).extracting(MethodSpec::name)
            .doesNotContain("dslForIfPermitted", "fanOutDomain");
    }

    @Test
    void multiTenantCarrierShipsTheRoutingStatics() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // Carrier resolution off the GraphQL context, failing loudly outside owned acquisition.
            .contains("static fake.code.generated.schema.OwnedConnections of(")
            .contains("graphql.schema.DataFetchingEnvironment env")
            // The divined-key fold: typed return, collection flattening, agreement guard.
            .contains("static java.lang.Integer divinedTenant(java.lang.Object... candidates)")
            .contains("Tenant bindings disagree within one operation")
            .contains("The tenant binding value is absent")
            // The build-time-path nested slot read.
            .contains("static java.lang.Object tenantSlot(java.lang.Object container, java.lang.String... path)")
            // The single loader-naming seam: bare path form plus the tenant-partitioned form
            // whose opaque segment keeps inherited-tenant batches tenant-homogeneous.
            .contains("static java.lang.String loaderName(")
            .contains("static java.lang.String tenantLoaderName(")
            .contains("loaderName(env, parentType) + \" tenant:\"");
    }

    @Test
    void singleTenantCarrierOmitsTheRoutingMachinery_butKeepsTheUnifiedResolution() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // Genuinely <tenantColumn>-only machinery stays absent in single-tenant builds.
            .doesNotContain("divinedTenant")
            .doesNotContain("tenantSlot")
            .doesNotContain("scatter(")
            .doesNotContain("SCATTER_WORKER")
            .doesNotContain("timedOutTenants")
            // The unified per-key carrier is both topologies' one acquisition seam: the
            // single-tenant path resolves contexts through of(env) + dslDefault() (the one-key
            // case), and the untenanted slot is the map's Optional.empty() entry, never a field.
            .contains("static fake.code.generated.schema.OwnedConnections of(")
            .contains("dslDefault")
            .doesNotContain("defaultPinned");
    }

    @Test
    void multiTenantRuntimeShipsTheFanOutConfigurationSurface() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

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
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
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
            .contains("public void abort()");
    }

    @Test
    void multiTenantCarrierShipsTheFanOutHelpers() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty(), ClassName.get(Integer.class),
            TenantColumnFixtures.INTEGER_KEY_COLUMN);

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            // Domain: the source's tenants(), read once per request, in its given order.
            .contains("static java.util.List<java.lang.Integer> fanOutDomain(")
            .contains("new java.util.ArrayList<>(carrier.readDomain())")
            .contains("canonical.add(canonicalTenantKey(tenant))")
            // Union with per-element tenant stamping, failures appended after successful rows.
            .contains("static <R> java.util.List<java.lang.Object> fanOutRows(")
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

        // The one per-element stamp, shared by the fan-out union and node and entity dispatch,
        // typed on the build's tenant key so it is no general-purpose way into the slot.
        var handDown = method(type(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME),
            ConnectionRuntimeClassGenerator.HAND_DOWN_METHOD);
        assertThat(handDown.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(handDown.returnType().toString()).isEqualTo("graphql.execution.DataFetcherResult<T>");
        assertThat(parameters(handDown)).containsExactly("T row", "java.lang.Integer tenantKey");

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .as("the domain is the source's, not a set of keys the runtime hosts")
            .doesNotContain("tenantKeys");
    }

    @Test
    void singleTenantOmitsTheFanOutSubstrate() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());

        var runtime = render(units, ConnectionRuntimeClassGenerator.RUNTIME_CLASS_NAME);
        assertThat(runtime)
            .doesNotContain("fanOut")
            .doesNotContain("FAN_OUT")
            .doesNotContain("tenantKeys")
            .doesNotContain("java.time.Duration");

        // The concurrent map itself is not fan-out substrate any more: the unified carrier uses
        // one ConcurrentHashMap in both topologies (computeIfAbsent is the only minting
        // mechanism), so only the genuinely <tenantColumn>-gated machinery is asserted absent.
        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier)
            .doesNotContain("scatter(")
            .doesNotContain("SCATTER_WORKER")
            .doesNotContain("Outcome")
            .doesNotContain("timedOutTenants");

        // The abort seam is not fan-out substrate either: a pin that completes after the carrier
        // released aborts its own connection in both topologies.
        var pinned = render(units, ConnectionRuntimeClassGenerator.PINNED_CONNECTION_CLASS_NAME);
        assertThat(pinned).contains("public void abort()");
    }

    @Test
    void singleTenantKeepsTheErasedObjectShape() {
        var units = ConnectionRuntimeClassGenerator.generate(
            "fake.code.generated", SessionIdentityRows.empty());

        var key = render(units, "ConnectionKey");
        assertThat(key)
            .contains("java.util.Optional<java.lang.Object> tenant")
            .contains("forTenant(java.lang.Object tenant)");

        var carrier = render(units, ConnectionRuntimeClassGenerator.OWNED_CONNECTIONS_CLASS_NAME);
        assertThat(carrier).contains("dslFor(java.lang.Object tenantKey");
    }
}
