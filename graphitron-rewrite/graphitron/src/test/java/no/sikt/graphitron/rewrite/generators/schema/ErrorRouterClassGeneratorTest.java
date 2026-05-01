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
 * {@code ErrorRouter} class. Asserts the dispatch arm and sealed-style {@code Mapping} taxonomy
 * are present in the produced {@link TypeSpec}, that the catch-arm signature matches §3, and
 * that runtime invariants (no top-level handler, validation arm precedes source-order match)
 * are reachable through the generated method shapes.
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
    void emits_mappingNestedInterfaceWithBuildMatchAndDescription() {
        var router = generate();
        TypeSpec mapping = nested(router, "Mapping");
        assertThat(mapping.kind().name()).isEqualTo("INTERFACE");
        assertThat(mapping.methodSpecs()).extracting(MethodSpec::name)
            .containsExactlyInAnyOrder("build", "match", "description");
    }

    @Test
    void emits_fourConcreteMappingClasses() {
        var router = generate();
        var nestedNames = router.typeSpecs().stream().map(TypeSpec::name).toList();
        assertThat(nestedNames).containsExactlyInAnyOrder(
            "Mapping",
            "ExceptionMapping",
            "SqlStateMapping",
            "VendorCodeMapping",
            "ValidationMapping");
    }

    @Test
    void exceptionMapping_carriesClassMatchesDescriptionFactory_andOverridesMatchBuildDescription() {
        var router = generate();
        var em = nested(router, "ExceptionMapping");
        assertThat(em.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        assertThat(em.fieldSpecs()).extracting(f -> f.name())
            .containsExactly("exceptionClass", "matches", "description", "factory");
        assertThat(em.methodSpecs()).extracting(MethodSpec::name)
            .contains("match", "build", "description");
    }

    @Test
    void sqlStateMapping_matchKeysOnSQLException_andSqlStateAccessor() {
        var router = generate();
        var sm = nested(router, "SqlStateMapping");
        var match = method(sm, "match");
        // Code emission detail: must check instanceof SQLException and consult getSQLState().
        String body = match.code().toString();
        assertThat(body).contains("instanceof");
        assertThat(body).contains("SQLException");
        assertThat(body).contains("getSQLState");
    }

    @Test
    void vendorCodeMapping_matchKeysOnSQLException_andStringifiesErrorCode() {
        var router = generate();
        var vm = nested(router, "VendorCodeMapping");
        var match = method(vm, "match");
        String body = match.code().toString();
        assertThat(body).contains("SQLException");
        assertThat(body).contains("getErrorCode");
        // String.valueOf(...) is the agreed coercion; vendor codes are emitted as Strings on
        // the @error directive but graphql-java surfaces SQLException.getErrorCode() as int.
        assertThat(body).contains("valueOf");
    }

    @Test
    void validationMapping_matchAlwaysReturnsFalse() {
        var router = generate();
        var vm = nested(router, "ValidationMapping");
        var match = method(vm, "match");
        assertThat(match.code().toString()).contains("return false");
    }

    @Test
    void dispatch_signature_matchesSpec() {
        var router = generate();
        var dispatch = method(router, "dispatch");
        assertThat(dispatch.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(dispatch.parameters()).extracting(p -> p.name())
            .containsExactly("thrown", "mappings", "env", "payloadFactory");
        // Generic on P; both arms return DataFetcherResult<P>.
        assertThat(dispatch.returnType().toString())
            .isEqualTo("graphql.execution.DataFetcherResult<P>");
    }

    @Test
    void dispatch_validationArmPrecedesSourceOrderMatchLoop() {
        var router = generate();
        var dispatch = method(router, "dispatch");
        String body = dispatch.code().toString();
        int validationArmIdx = body.indexOf("ValidationViolationGraphQLException");
        int matchLoopIdx = body.indexOf("mapping.match(t)");
        assertThat(validationArmIdx).isPositive();
        assertThat(matchLoopIdx).isPositive();
        assertThat(validationArmIdx)
            .as("Validation arm must run ahead of MAPPINGS iteration (R12 §3)")
            .isLessThan(matchLoopIdx);
    }

    @Test
    void dispatch_unmatchedFallsThroughToRedact() {
        var router = generate();
        var dispatch = method(router, "dispatch");
        // The final unmatched arm forwards to redact; no rethrow path.
        assertThat(dispatch.code().toString()).contains("redact(thrown, env)");
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
