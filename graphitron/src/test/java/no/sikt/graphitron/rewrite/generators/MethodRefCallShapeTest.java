package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.rewrite.model.MethodRef;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural-tier coverage for the static-vs-instance fork on {@code @service} methods.
 * Replaces the body-string assertions retired from {@code TypeFetcherGeneratorTest} with
 * direct exercise of {@code TypeFetcherGenerator#serviceCallTarget} (widened to package-private)
 * over the two {@link MethodRef.CallShape} arms. The classifier-side mapping from real Java
 * methods to {@link MethodRef.Service#callShape()} arms is covered separately by
 * {@code ServiceCatalogTest} (which has same-package access to {@code ServiceCatalog}); the two
 * tests together pin the model→emit dispatch end to end without re-using {@code CodeBlock.equals}
 * (which is {@code toString}-based at
 * {@code graphitron-javapoet/src/main/java/no/sikt/graphitron/javapoet/CodeBlock.java:107}, the
 * same fragility class as substring-on-emitted-code).
 *
 * <p>Emit-shape regression backstop is the {@code graphitron-sakila-example} compilation tier.
 */
@UnitTier
class MethodRefCallShapeTest {

    @Test
    void serviceCallTarget_staticArm_routesToBareClassEmitForm() {
        // The Static arm emits `ClassName.method(...)`, so the returned CodeBlock must render as
        // the bare class type symbol (no constructor invocation). Asserting via toString here
        // checks the model dispatch shape, not a generated method body — and a structural change
        // to the emit form would also break the compile-tier fixture in graphitron-sakila-example.
        var serviceClass = ClassName.get("com.example", "Service");
        var staticService = new MethodRef.Service(
            "com.example.Service", "doThing", TypeName.OBJECT,
            List.of(), List.of(), new MethodRef.CallShape.Static(false));

        var code = TypeFetcherGenerator.serviceCallTarget(staticService, serviceClass);
        assertThat(code.toString())
            .as("Static arm dispatches to the bare class call-target")
            .isEqualTo("com.example.Service");
    }

    @Test
    void serviceCallTarget_instanceWithDslHolderArm_routesToNewServiceWithDsl() {
        var serviceClass = ClassName.get("com.example", "Service");
        var instanceService = new MethodRef.Service(
            "com.example.Service", "doThing", TypeName.OBJECT,
            List.of(), List.of(), new MethodRef.CallShape.InstanceWithDslHolder());

        var code = TypeFetcherGenerator.serviceCallTarget(instanceService, serviceClass);
        assertThat(code.toString())
            .as("InstanceWithDslHolder arm dispatches to the per-call holder construction")
            .isEqualTo("new com.example.Service(dsl)");
    }

    @Test
    void serviceCallTarget_multiArgHolderCtor_rendersDslAndContextArgs() {
        // A (DSLContext, tenantId) holder ctor renders both args — `dsl` for the DSLContext
        // slot and an inline getContextArgument extraction for the context slot.
        var serviceClass = ClassName.get("com.example", "Service");
        var holder = new MethodRef.CallShape.InstanceWithDslHolder(List.of(
            new MethodRef.Param.Typed("ctx", "org.jooq.DSLContext",
                ClassName.get("org.jooq", "DSLContext"),
                new no.sikt.graphitron.rewrite.model.ParamSource.DslContext()),
            new MethodRef.Param.Typed("tenantId", "java.lang.String",
                ClassName.get(String.class),
                new no.sikt.graphitron.rewrite.model.ParamSource.Context())));
        var instanceService = new MethodRef.Service(
            "com.example.Service", "doThing", TypeName.OBJECT, List.of(), List.of(), holder);

        var code = TypeFetcherGenerator.serviceCallTarget(instanceService, serviceClass);
        assertThat(code.toString())
            .as("multi-arg holder ctor renders dsl plus an inline context extraction")
            .isEqualTo("new com.example.Service(dsl, (java.lang.String) graphitronContext(env).getContextArgument(env, \"tenantId\"))");
    }

    @Test
    void needsDsl_contextOnlyHolderCtor_isFalse() {
        // A holder ctor with no DSLContext slot (context-only) needs no dsl local.
        var holder = new MethodRef.CallShape.InstanceWithDslHolder(List.of(
            new MethodRef.Param.Typed("tenantId", "java.lang.String",
                ClassName.get(String.class),
                new no.sikt.graphitron.rewrite.model.ParamSource.Context())));
        assertThat(TypeFetcherGenerator.needsDsl(holder))
            .as("context-only holder ctor does not need the dsl local")
            .isFalse();
    }

    @Test
    void needsDsl_staticArm_readsTheCallShapeFlag() {
        // Static.needsDslLocal() is the pre-resolved disjunction "any param has DslContext".
        // Both buildServiceFetcherCommon and buildServiceRowsMethod read needsDsl through this
        // helper, so the boolean lives in one place.
        assertThat(TypeFetcherGenerator.needsDsl(new MethodRef.CallShape.Static(false)))
            .as("Static(needsDslLocal=false) yields false")
            .isFalse();
        assertThat(TypeFetcherGenerator.needsDsl(new MethodRef.CallShape.Static(true)))
            .as("Static(needsDslLocal=true) yields true")
            .isTrue();
    }

    @Test
    void needsDsl_instanceWithDslHolderArm_alwaysTrue() {
        assertThat(TypeFetcherGenerator.needsDsl(new MethodRef.CallShape.InstanceWithDslHolder()))
            .as("InstanceWithDslHolder always needs the dsl local — the holder ctor takes it")
            .isTrue();
    }

    @Test
    void callShape_sealedSwitchIsExhaustive() {
        // Compile-time guarantee: the sealed `CallShape` permits exactly two arms, so any
        // sealed-switch refactor that drops one arm becomes a compile error at every consumer
        // (serviceCallTarget, needsDsl). This test pins the permits surface itself; if a third
        // arm is added in the future, the binding here breaks loudly until every consumer
        // updates to handle it.
        Class<?>[] permitted = MethodRef.CallShape.class.getPermittedSubclasses();
        assertThat(permitted)
            .as("CallShape permits exactly Static and InstanceWithDslHolder")
            .containsExactlyInAnyOrder(
                MethodRef.CallShape.Static.class,
                MethodRef.CallShape.InstanceWithDslHolder.class);
    }
}
