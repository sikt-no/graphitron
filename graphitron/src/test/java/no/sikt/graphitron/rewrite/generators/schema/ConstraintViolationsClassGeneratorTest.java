package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for {@link ConstraintViolationsClassGenerator}: SDL-independent emission of the
 * helper that builds a validation {@code GraphQLError}. Asserts the emitted class and method
 * signatures only; what the methods return (message, path, {@code extensions.constraint}) is
 * pinned against the generated class by {@code ConstraintViolationsWireShapeTest} in
 * {@code graphitron-sakila-example}.
 */
@UnitTier
class ConstraintViolationsClassGeneratorTest {

    @Test
    void emits_singleConstraintViolationsClass() {
        var specs = ConstraintViolationsClassGenerator.generate("com.example.gen");
        assertThat(specs).hasSize(1);
        assertThat(specs.get(0).name()).isEqualTo("ConstraintViolations");
        assertThat(specs.get(0).modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void toGraphQLError_signatureMatchesSpec() {
        var helper = generate();
        var m = method(helper, "toGraphQLError");
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.parameters()).extracting(p -> p.name())
            .containsExactly("violation", "env", "argPath", "paths");
        assertThat(m.parameters().get(2).type().toString())
            .as("the argument path, the same parameter shape sizeExceeded takes")
            .isEqualTo("java.util.List<java.lang.Object>");
        assertThat(m.parameters().get(3).type().toString())
            .as("the member-to-SDL-field translation, the nested Paths type")
            .isEqualTo("com.example.gen.schema.ConstraintViolations.Paths");
        assertThat(m.returnType().toString()).isEqualTo("graphql.GraphQLError");
    }

    @Test
    void sizeExceeded_signatureAndTheSharedBuildersVisibility() {
        // The wire shape both public methods produce is pinned behaviourally by
        // ConstraintViolationsWireShapeTest in graphitron-sakila-example, against the generated class.
        var helper = generate();
        var m = method(helper, "sizeExceeded");
        assertThat(m.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(m.parameters()).extracting(p -> p.name()).containsExactly("env", "argPath", "max");
        assertThat(m.returnType().toString()).isEqualTo("graphql.GraphQLError");
        assertThat(method(helper, "validationError").modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
    }

    private static TypeSpec generate() {
        return ConstraintViolationsClassGenerator.generate("com.example.gen").get(0);
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> name.equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method named " + name));
    }
}
