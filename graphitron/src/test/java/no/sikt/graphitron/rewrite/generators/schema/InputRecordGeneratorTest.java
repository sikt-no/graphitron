package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-tier shape coverage for {@link InputRecordGenerator}: emission package, public-class
 * visibility, presence of the {@code fromMap} factory and component accessors. Behavioural
 * coverage (the reachable-closure walk, list / nested / unreachable handling, validator
 * pre-step rewire) lives at the pipeline tier in {@code FetcherPipelineTest}.
 */
@UnitTier
class InputRecordGeneratorTest {

    @Test
    void emits_publicClassInInputsPackage() {
        var spec = onlySpec("""
            input FilmIdInput { filmId: Int! }
            type Query { dummy(in: FilmIdInput): String }
            """, "FilmIdInput");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void emits_fromMapFactoryWithMapStringObjectSignature() {
        var spec = onlySpec("""
            input FilmIdInput { filmId: Int! }
            type Query { dummy(in: FilmIdInput): String }
            """, "FilmIdInput");
        MethodSpec fromMap = method(spec, "fromMap");
        assertThat(fromMap.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        // The factory takes Map<String,Object> and returns the emitted class itself.
        assertThat(fromMap.parameters()).hasSize(1);
        assertThat(fromMap.parameters().get(0).type().toString())
            .isEqualTo("java.util.Map<java.lang.String, java.lang.Object>");
        assertThat(fromMap.returnType().toString())
            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".inputs.FilmIdInput");
    }

    @Test
    void emits_oneAccessorPerSdlComponent() {
        var spec = onlySpec("""
            input FilmDetails { id: Int!, title: String, length: Int }
            type Query { dummy(in: FilmDetails): String }
            """, "FilmDetails");
        // Component-name accessors carry the SDL field name verbatim; the validator's
        // ConstraintViolation.getPropertyPath() walks these accessors.
        assertThat(spec.methodSpecs())
            .extracting(MethodSpec::name)
            .contains("id", "title", "length", "fromMap");
    }

    private TypeSpec onlySpec(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        List<TypeSpec> specs = InputRecordGenerator.generate(
            bundle.model(), bundle.assembled(), DEFAULT_OUTPUT_PACKAGE);
        return specs.stream()
            .filter(t -> t.name().equals(typeName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Expected input class '" + typeName + "' but got: "
                    + specs.stream().map(TypeSpec::name).toList()));
    }

    private MethodSpec method(TypeSpec spec, String name) {
        return spec.methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Method '" + name + "' not found on " + spec.name()));
    }
}
