package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;

class GraphitronContextInterfaceGeneratorTest {

    @Test
    void generate_returnsExactlyOneType() {
        assertThat(GraphitronContextInterfaceGenerator.generate()).hasSize(1);
    }

    @Test
    void generatedType_isInterfaceNamedGraphitronContext() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        assertThat(spec.name()).isEqualTo("GraphitronContext");
        assertThat(spec.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC);
    }

    @Test
    void generatedInterface_hasThreeMethods() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactlyInAnyOrder("getDslContext", "getContextArgument", "getTenantId");
    }

    @Test
    void getDslContext_returnsDSLContextAndTakesEnv() {
        var method = findMethod("getDslContext");
        assertThat(method.returnType().toString()).isEqualTo("org.jooq.DSLContext");
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("graphql.schema.DataFetchingEnvironment");
        assertThat(method.modifiers()).contains(Modifier.ABSTRACT);
    }

    @Test
    void getContextArgument_isGenericWithTypeParameterT() {
        var method = findMethod("getContextArgument");
        assertThat(method.returnType().toString()).isEqualTo("T");
        assertThat(method.typeVariables()).extracting(v -> v.name()).containsExactly("T");
        assertThat(method.parameters()).extracting(p -> p.name()).containsExactly("env", "name");
    }

    @Test
    void getTenantId_hasDefaultImplementationReturningEmptyString() {
        var method = findMethod("getTenantId");
        assertThat(method.modifiers()).contains(Modifier.DEFAULT);
        assertThat(method.returnType().toString()).isEqualTo("java.lang.String");
        assertThat(method.code().toString()).contains("return \"\"");
    }

    private static no.sikt.graphitron.javapoet.MethodSpec findMethod(String name) {
        return GraphitronContextInterfaceGenerator.generate().get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
