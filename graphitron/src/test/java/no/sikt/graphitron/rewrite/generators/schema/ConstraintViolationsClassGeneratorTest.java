package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline test for {@link ConstraintViolationsClassGenerator}: SDL-independent emission of the
 * wrapper-side helper that translates a {@code ConstraintViolation} into a
 * {@code GraphQLError}. Asserts the method's signature, the path-splicing arms, and the
 * {@code extensions["constraint"]} population that lights up when a VALIDATION-handled
 * {@code @error} type declares an {@code extensions} field (§5).
 */
@UnitTier
class ConstraintViolationsClassGeneratorTest {

    @Test
    void emits_singleConstraintViolationsClass() {
        var specs = ConstraintViolationsClassGenerator.generate();
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
            .containsExactly("violation", "env", "argName");
        assertThat(m.returnType().toString()).isEqualTo("graphql.GraphQLError");
    }

    @Test
    void toGraphQLError_buildsPathFromExecutionStepInfoAndArgNameAndPropertyPath() {
        var helper = generate();
        var body = method(helper, "toGraphQLError").code().toString();
        assertThat(body).contains("env.getExecutionStepInfo().getPath().toList()");
        assertThat(body).contains("path.add(argName)");
        assertThat(body).contains("violation.getPropertyPath()");
        // CONTAINER_ELEMENT vs PROPERTY/BEAN arms produce list-index/map-key and property-name
        // entries respectively (matches §5's property-path → response-path projection).
        assertThat(body).contains("CONTAINER_ELEMENT");
        assertThat(body).contains("node.getIndex()");
        assertThat(body).contains("node.getKey()");
        assertThat(body).contains("node.getName()");
    }

    @Test
    void toGraphQLError_populatesExtensionsWithConstraintAnnotationSimpleName() {
        // §5 extensions.constraint: the helper builds an extensions map keyed by "constraint"
        // and populated from violation.getConstraintDescriptor().getAnnotation().annotationType()
        // .getSimpleName(). The wrapper-side write is unconditional; schema authors gate client
        // visibility by declaring (or omitting) an extensions-shaped field on their @error type.
        var helper = generate();
        var body = method(helper, "toGraphQLError").code().toString();
        assertThat(body).contains("LinkedHashMap");
        assertThat(body).contains("extensions.put(\"constraint\"");
        assertThat(body).contains("violation.getConstraintDescriptor()");
        assertThat(body).contains("getAnnotation()");
        assertThat(body).contains("annotationType()");
        assertThat(body).contains("getSimpleName()");
        // Builder chain wires the map onto the GraphQLError.
        assertThat(body).contains(".extensions(extensions)");
    }

    private static TypeSpec generate() {
        return ConstraintViolationsClassGenerator.generate().get(0);
    }

    private static MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> name.equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no method named " + name));
    }
}
