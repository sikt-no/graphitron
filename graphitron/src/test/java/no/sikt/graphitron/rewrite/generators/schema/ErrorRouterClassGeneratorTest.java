package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline test for {@link ErrorRouterClassGenerator}: SDL-independent emission of the runtime
 * {@code ErrorRouter} class. Asserts the claim arm and sealed-style {@code Mapping} taxonomy
 * are present in the produced {@link TypeSpec}, that the catch-arm signature matches §3, and
 * that runtime invariants (no top-level handler, source-order first-match dispatch) are
 * reachable through the generated method shapes.
 */
@UnitTier
class ErrorRouterClassGeneratorTest {

    @Test
    void emits_singleErrorRouterClass() {
        var specs = ErrorRouterClassGenerator.generate("com.example");
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("ErrorRouter");
        assertThat(specs.get(0).modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void emits_mappingNestedInterfaceWithMatchAndDescription() {
        // Source-direct dispatch: Mapping has match() + description() only. The legacy
        // build(path, message) factory is gone; the matched source goes into the errors list
        // directly and graphql-java's PropertyDataFetcher reads each declared @error field
        // off the source.
        var router = generate();
        TypeSpec mapping = nested(router, "Mapping");
        assertThat(mapping.kind().name()).isEqualTo("INTERFACE");
        assertThat(mapping.methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder("match", "description");
    }

    @Test
    void mapping_match_takesTheThrowAndTheCandidate() {
        // A DATABASE mapping's matches text is tested against every link from the outermost
        // throw down to the candidate, so match sees both.
        var match = method(nested(generate(), "Mapping"), "match");
        assertThat(match.returnType().toString()).isEqualTo("boolean");
        assertThat(match.parameters()).extracting(p -> p.type().toString())
            .containsExactly("java.lang.Throwable", "java.lang.Throwable");
        assertThat(match.parameters()).extracting(p -> p.name())
            .containsExactly("thrown", "candidate");
    }

    @Test
    void emits_twoConcreteMappingClassesAndThePrivateChainRootHolder() {
        // ValidationMapping retired with §5: validation runs as a wrapper pre-execution step,
        // not via the dispatcher, so its Mapping shape is no longer needed. Every DATABASE
        // discriminator is one DatabaseMapping.
        var router = generate();
        var nestedNames = router.typeSpecs().stream().map(TypeSpec::name).toList();
        assertThat(nestedNames).containsExactlyInAnyOrder(
            "Mapping",
            "ExceptionMapping",
            "DatabaseMapping",
            "ChainRoots");
        assertThat(nested(router, "ChainRoots").modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
    }

    @Test
    void exceptionMapping_carriesClassMatchesDescription_andOverridesMatchAndDescription() {
        var router = generate();
        var em = nested(router, "ExceptionMapping");
        assertThat(em.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        assertThat(em.fieldSpecs()).extracting(f -> f.name())
            .containsExactly("exceptionClass", "matches", "description");
        assertThat(em.methodSpecs()).extracting(MethodSpec::name)
            .contains("match", "description")
            .doesNotContain("build");
    }

    @Test
    void databaseMapping_carriesBothDiscriminatorsMatchesAndDescription() {
        var router = generate();
        var dm = nested(router, "DatabaseMapping");
        assertThat(dm.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        assertThat(dm.fieldSpecs()).extracting(f -> f.name())
            .containsExactly("sqlState", "vendorCode", "matches", "description");
        var ctor = dm.methodSpecs().stream().filter(MethodSpec::isConstructor).findFirst().orElseThrow();
        assertThat(ctor.parameters()).extracting(p -> p.name())
            .containsExactly("sqlState", "vendorCode", "matches", "description");
        assertThat(method(dm, "match").parameters()).extracting(p -> p.name())
            .containsExactly("thrown", "candidate");
    }

    @Test
    void claim_signature_returnsTheClaimedThrowable() {
        var claim = method(generate(), "claim");
        assertThat(claim.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(claim.returnType().toString()).isEqualTo("java.lang.Throwable");
        assertThat(claim.parameters()).extracting(p -> p.type().toString()).containsExactly(
            "java.lang.Throwable",
            "Mapping[]",
            "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void matchesSource_signature_takesTheMappingTheSourceAndTheContext() {
        var matchesSource = method(generate(), "matchesSource");
        assertThat(matchesSource.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(matchesSource.returnType().toString()).isEqualTo("boolean");
        assertThat(matchesSource.parameters()).extracting(p -> p.type().toString()).containsExactly(
            "Mapping",
            "java.lang.Throwable",
            "graphql.GraphQLContext");
    }

    @Test
    void redact_logsCorrelationIdAndReturnsRedactedResult() {
        var router = generate();
        var redact = method(router, "redact");
        String body = redact.code().toString();
        assertThat(body)
            .contains("UUID.randomUUID()")
            .contains("LOGGER.error")
            .contains("An error occurred. Reference: ")
            .doesNotContain("thrown.getMessage()");
    }

    @Test
    void toConfiguredLog_signature_takesTheEnvironmentTheReferenceAndTheLogCall() {
        var router = generate();
        var toConfiguredLog = method(router, "toConfiguredLog");
        assertThat(toConfiguredLog.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(toConfiguredLog.returnType().toString()).isEqualTo("boolean");
        assertThat(toConfiguredLog.parameters()).extracting(p -> p.type().toString()).containsExactly(
            "graphql.schema.DataFetchingEnvironment",
            "java.lang.String",
            "java.util.function.Consumer<com.example.schema.FetcherErrorLog>");
    }

    @Test
    void surfaceClientErrorOrRedact_surfacesClientErrorElseFallsThroughToRedact() {
        // The no-channel disposition surfaces a GraphitronClientException's real message and
        // otherwise reuses redact unchanged (the privacy contract for internal faults).
        var router = generate();
        var surface = method(router, "surfaceClientErrorOrRedact");
        String body = surface.code().toString();
        assertThat(body)
            .contains("t = t.getCause()")
            .contains("instanceof com.example.schema.GraphitronClientException")
            .contains("return redact(thrown, env)");
    }

    @Test
    void noChannelRouterCall_emitsSurfaceClientErrorOrRedact() {
        // The one definition of the no-channel disposition every emit site consults —
        // sync catch arms and async .exceptionally arms alike route through
        // surfaceClientErrorOrRedact, parameterised on the local throwable's name.
        assertThat(ErrorRouterClassGenerator.noChannelRouterCall("com.example", "e").toString())
            .isEqualTo("com.example.schema.ErrorRouter.surfaceClientErrorOrRedact(e, env)");
        assertThat(ErrorRouterClassGenerator.noChannelRouterCall("com.example", "t").toString())
            .isEqualTo("com.example.schema.ErrorRouter.surfaceClientErrorOrRedact(t, env)");
    }

    @Test
    void noConstructor_otherThanPrivateNoArg() {
        var router = generate();
        var ctors = router.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .toList();
        assertThat(ctors).hasSize(1);
        assertThat(ctors.get(0).modifiers()).contains(Modifier.PRIVATE);
        assertThat(ctors.get(0).parameters()).isEmpty();
    }

    private static TypeSpec generate() {
        List<TypeSpec> specs = ErrorRouterClassGenerator.generate("com.example");
        return specs.get(0);
    }

    private static TypeSpec nested(TypeSpec parent, String name) {
        return parent.typeSpecs().stream()
            .filter(t -> name.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "nested type '" + name + "' not found in " + parent.name()
                    + " (have: " + parent.typeSpecs().stream().map(TypeSpec::name).toList() + ")"));
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> name.equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "method '" + name + "' not found on " + spec.name()
                    + " (have: " + spec.methodSpecs().stream().map(MethodSpec::name).toList() + ")"));
    }
}
