package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
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
    void generatedInterface_hasFourMethods() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactlyInAnyOrder("getDslContext", "getContextArgument", "getTenantId", "getValidator");
    }

    @Test
    void getValidator_hasDefaultImplementationReturningHolderInstance() {
        var method = findMethod("getValidator");
        assertThat(method.modifiers()).contains(Modifier.DEFAULT);
        assertThat(method.returnType().toString()).isEqualTo("jakarta.validation.Validator");
        assertThat(method.code().toString()).contains("DefaultValidatorHolder.INSTANCE");
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
    void getContextArgument_hasDefaultImplementationReadingGraphQLContext() {
        var method = findMethod("getContextArgument");
        assertThat(method.modifiers()).contains(Modifier.DEFAULT).doesNotContain(Modifier.ABSTRACT);
        assertThat(method.code().toString()).contains("env.getGraphQlContext().get(name)");
    }

    /**
     * Load-bearing: the {@code Graphitron.newExecutionInput(DSLContext)} lambda form
     * {@code (GraphitronContext) env -> dsl} relies on this interface having exactly
     * one abstract method. Adding another abstract method silently breaks the lambda
     * form; any such change must update {@code GraphitronFacadeGenerator} in tandem.
     */
    @Test
    void generatedInterface_hasExactlyOneAbstractMethod() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        var abstractMethods = spec.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.ABSTRACT))
            .toList();
        assertThat(abstractMethods).hasSize(1);
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
