package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline test for {@link GraphitronClientExceptionClassGenerator}: SDL-independent emission of the
 * generated client-error marker. Pins that the type subclasses {@code GraphqlErrorException} (so it
 * <em>is</em> a {@code GraphQLError}, channel-matchable and natively serialisable) and carries a
 * message through the library builder, the two properties R378's surfacing arm relies on.
 */
@UnitTier
class GraphitronClientExceptionClassGeneratorTest {

    @Test
    void emits_singleFinalSubclassOfGraphqlErrorException() {
        var specs = GraphitronClientExceptionClassGenerator.generate();
        assertThat(specs).hasSize(1);
        TypeSpec spec = specs.get(0);
        assertThat(spec.name()).isEqualTo("GraphitronClientException");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
        assertThat(spec.superclass().toString()).isEqualTo("graphql.GraphqlErrorException");
    }

    @Test
    void emits_publicMessageCarryingConstructor() {
        TypeSpec spec = GraphitronClientExceptionClassGenerator.generate().get(0);
        MethodSpec ctor = spec.methodSpecs().stream()
            .filter(MethodSpec::isConstructor)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no constructor emitted"));
        assertThat(ctor.modifiers()).contains(Modifier.PUBLIC);
        assertThat(ctor.parameters()).extracting(p -> p.name()).containsExactly("message");
        // The message rides the library's builder so the response error carries it natively.
        assertThat(ctor.code().toString())
            .contains("super(graphql.GraphqlErrorException.newErrorException().message(message))");
    }
}
