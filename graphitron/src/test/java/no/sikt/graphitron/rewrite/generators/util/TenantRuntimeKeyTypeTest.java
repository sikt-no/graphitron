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
            .doesNotContain("DataFetchingEnvironment");
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
