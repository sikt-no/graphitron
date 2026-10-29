package no.sikt.graphitron.rewrite.generators.schema;

import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Pipeline test for {@link ZeroRowsMatchedExceptionClassGenerator}: SDL-independent emission of the
 * exception the payload-returning bulk UPDATE throws after every row has run. Pins the
 * {@code IllegalStateException} superclass a {@code GENERIC} handler keeps matching through, the
 * constructor the fetcher calls, and the four accessors a consumer branches on. The message itself
 * is pinned at the execution tier.
 */
@UnitTier
class ZeroRowsMatchedExceptionClassGeneratorTest {

    private static TypeSpec spec() {
        var specs = ZeroRowsMatchedExceptionClassGenerator.generate();
        assertThat(specs).hasSize(1);
        return specs.get(0);
    }

    @Test
    void emits_singleFinalSubclassOfIllegalStateException() {
        TypeSpec spec = spec();
        assertThat(spec.name()).isEqualTo("ZeroRowsMatchedException");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(spec.superclass().toString()).isEqualTo("java.lang.IllegalStateException");
    }

    @Test
    void emits_theConstructorTheFetcherCalls() {
        var ctors = spec().methodSpecs().stream().filter(MethodSpec::isConstructor).toList();
        assertThat(ctors).hasSize(1);
        MethodSpec ctor = ctors.get(0);
        assertThat(ctor.modifiers()).contains(Modifier.PUBLIC);
        assertThat(ctor.parameters()).extracting(p -> p.name(), p -> p.type().toString()).containsExactly(
            tuple("verb", "java.lang.String"),
            tuple("fieldCoordinate", "java.lang.String"),
            tuple("missedRowIndices", "java.util.List<java.lang.Integer>"),
            tuple("rowCount", "int"));
    }

    @Test
    void emits_theFourAccessors() {
        assertThat(spec().methodSpecs()).filteredOn(m -> !m.isConstructor())
            .extracting(m -> m.name(), m -> m.returnType().toString(), m -> m.parameters().size())
            .containsExactlyInAnyOrder(
                tuple("fieldCoordinate", "java.lang.String", 0),
                tuple("rowCount", "int", 0),
                tuple("missedRowIndices", "java.util.List<java.lang.Integer>", 0),
                tuple("missCount", "int", 0));
    }
}
