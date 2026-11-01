package no.sikt.graphitron.rewrite.generators.util;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.emit.SessionIdentityRows;
import no.sikt.graphitron.rewrite.session.SessionIdentityFixtures;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every owned-connection build emits to settle a mutation field and report it: the carrier's
 * field-scope members, its nested-failure exception, and the instrumentation's result rewrite.
 * They are not conditional on a tenant column or on a session mount, since a build without either
 * still runs one transaction per top-level mutation field. Read off the emitted {@code TypeSpec}s,
 * never off rendered source.
 */
@PipelineTier
class OwnedBuildSettleSurfaceTest {

    private static final String PACKAGE = "fake.code.generated";

    @Test
    void aSingleTenantBuildWithNoSessionMount_emitsTheSettleSurface() {
        assertEmitsTheSettleSurface(
            ConnectionRuntimeClassGenerator.generate(PACKAGE, SessionIdentityRows.empty()),
            GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, SessionIdentityRows.empty()));
    }

    @Test
    void aSingleTenantBuildWithASessionMount_emitsTheSettleSurface() {
        var hooks = SessionIdentityFixtures.recording();
        assertEmitsTheSettleSurface(
            ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks),
            GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, null, hooks));
    }

    @Test
    void aMultiTenantBuild_emitsTheSettleSurface() {
        var hooks = SessionIdentityFixtures.recording();
        assertEmitsTheSettleSurface(
            ConnectionRuntimeClassGenerator.generate(PACKAGE, hooks, ClassName.get(String.class),
                TenantColumnFixtures.STRING_KEY_COLUMN),
            GraphitronConnectionInstrumentationGenerator.generate(PACKAGE, ClassName.get(String.class), hooks));
    }

    private static void assertEmitsTheSettleSurface(List<TypeSpec> runtime, List<TypeSpec> instrumentation) {
        TypeSpec carrier = unit(runtime, ConnectionRuntimeClassGenerator.TENANT_CONNECTIONS_CLASS_NAME);
        assertThat(methodNames(carrier))
            .as("the carrier opens, marks and settles a field's scope in every owned build")
            .contains("beginField", "settleField", "markFieldFailed");
        assertThat(carrier.typeSpecs().stream().map(TypeSpec::name))
            .as("and names a consumer scope's failure with its own exception")
            .contains("NestedScopeFailedException");

        TypeSpec instrumentationClass = unit(instrumentation, GraphitronConnectionInstrumentationGenerator.CLASS_NAME);
        assertThat(methodNames(instrumentationClass))
            .as("the instrumentation rewrites the result for a field that rolled back after it resolved")
            .contains("instrumentExecutionResult");
    }

    private static List<String> methodNames(TypeSpec spec) {
        return spec.methodSpecs().stream().map(MethodSpec::name).toList();
    }

    private static TypeSpec unit(List<TypeSpec> units, String name) {
        return units.stream().filter(u -> u.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError("no emitted unit named " + name + " in "
                + units.stream().map(TypeSpec::name).toList()));
    }
}
