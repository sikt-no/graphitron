package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link InputBeanResolver#boxPrimitive(String)}: pins the full 8-arm
 * primitive→wrapper mapping that the {@code FieldBinding.javaElementTypeName} invariant
 * ("real class name, never a primitive literal") depends on. The two
 * {@link no.sikt.graphitron.javapoet.ClassName#bestGuess(String)} consumers in
 * {@code InputBeanInstantiationEmitter} would throw on any primitive literal that slipped through;
 * this is the structural assertion that every primitive is covered, without growing the pipeline
 * fixture surface to one input bean per primitive.
 */
@UnitTier
class InputBeanResolverBoxPrimitiveTest {

    @ParameterizedTest
    @CsvSource({
        "int,     java.lang.Integer",
        "long,    java.lang.Long",
        "boolean, java.lang.Boolean",
        "double,  java.lang.Double",
        "float,   java.lang.Float",
        "short,   java.lang.Short",
        "byte,    java.lang.Byte",
        "char,    java.lang.Character",
    })
    void boxPrimitive_mapsEveryPrimitiveLiteralToItsWrapperFqn(String primitive, String wrapper) {
        assertThat(InputBeanResolver.boxPrimitive(primitive)).isEqualTo(wrapper);
    }

    @Test
    void boxPrimitive_passesClassNamesThroughUnchanged() {
        assertThat(InputBeanResolver.boxPrimitive("java.lang.String")).isEqualTo("java.lang.String");
        assertThat(InputBeanResolver.boxPrimitive("com.example.Foo")).isEqualTo("com.example.Foo");
    }

    @Test
    void boxPrimitive_doesNotBoxPrimitiveArrays() {
        // int[] is not in the switch and falls through; the loud "not loadable" path in tryLoad
        // is the existing safety net for arrays of primitives.
        assertThat(InputBeanResolver.boxPrimitive("int[]")).isEqualTo("int[]");
    }
}
