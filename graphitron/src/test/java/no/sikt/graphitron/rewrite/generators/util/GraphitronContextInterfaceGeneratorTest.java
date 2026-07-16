package no.sikt.graphitron.rewrite.generators.util;

import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphitronContextInterfaceGeneratorTest {

    @Test
    void generate_returnsExactlyOneTopLevelType() {
        // The impl is nested inside the interface (same-compilation-unit permits), so
        // only one top-level TypeSpec is emitted.
        assertThat(GraphitronContextInterfaceGenerator.generate()).hasSize(1);
    }

    @Test
    void generatedType_isSealedInterfaceNamedGraphitronContext() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        assertThat(spec.name()).isEqualTo("GraphitronContext");
        assertThat(spec.kind()).isEqualTo(TypeSpec.Kind.INTERFACE);
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.SEALED);
    }

    @Test
    void generatedInterface_hasThreeMethods() {
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        // GetTenantId is gone (multi-tenant routing reintroduces it on top of the sealed surface).
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactlyInAnyOrder("getDslContext", "getContextArgument", "getValidator");
    }

    @Test
    void getValidator_hasDefaultImplementationReturningHolderInstance() {
        var method = findMethod("getValidator");
        assertThat(method.modifiers()).contains(Modifier.DEFAULT);
        assertThat(method.returnType().toString()).isEqualTo("jakarta.validation.Validator");
        assertThat(method.code().toString()).contains("DefaultValidatorHolder.INSTANCE");
    }

    @Test
    void getDslContext_isDefaultReturningDSLContextAndTakesEnv() {
        var method = findMethod("getDslContext");
        assertThat(method.returnType().toString()).isEqualTo("org.jooq.DSLContext");
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("graphql.schema.DataFetchingEnvironment");
        // GetDslContext is a default method now; the impl reads off the GraphQLContext.
        assertThat(method.modifiers()).contains(Modifier.DEFAULT);
        assertThat(method.code().toString())
            .contains("env.getGraphQlContext().get(org.jooq.DSLContext.class)");
    }

    @Test
    void getContextArgument_takesEnvAndNameOnlyAndReturnsObject() {
        var method = findMethod("getContextArgument");
        // The Class<T> expectedType slot moved to a Java cast at the generated
        // call site; the singleton returns Object and the throw on missing-value stays as the
        // server-log diagnostic.
        assertThat(method.returnType().toString()).isEqualTo("java.lang.Object");
        assertThat(method.typeVariables()).isEmpty();
        assertThat(method.parameters()).extracting(p -> p.name())
            .containsExactly("env", "name");
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("graphql.schema.DataFetchingEnvironment");
        assertThat(method.parameters().get(1).type().toString())
            .isEqualTo("java.lang.String");
    }

    @Test
    void getContextArgument_defaultBodyReadsGraphQLContextAndThrowsOnMissing() {
        var method = findMethod("getContextArgument");
        assertThat(method.modifiers()).contains(Modifier.DEFAULT).doesNotContain(Modifier.ABSTRACT);
        String body = method.code().toString();
        assertThat(body)
            .contains("env.getGraphQlContext().get(name)")
            .contains("call Graphitron.newExecutionInput(...) to populate it");
    }

    @Test
    void generatedInterface_hasNoAbstractMethods() {
        // Every method on the sealed interface is default; the sealed-interface + singleton
        // impl shape replaces the legacy "one abstract method for the lambda overload" invariant.
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        var abstractMethods = spec.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.ABSTRACT))
            .toList();
        assertThat(abstractMethods).isEmpty();
    }

    @Test
    void generatedInterface_nestsGraphitronContextImpl() {
        // The sealed interface implicitly permits subclasses declared in the same compilation
        // unit; nesting the impl gives us that without javapoet permits support.
        TypeSpec spec = GraphitronContextInterfaceGenerator.generate().get(0);
        var nested = spec.typeSpecs().stream()
            .filter(t -> "GraphitronContextImpl".equals(t.name()))
            .findFirst()
            .orElseThrow();
        assertThat(nested.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
        assertThat(nested.fieldSpecs()).extracting(f -> f.name()).contains("INSTANCE");
    }

    private static no.sikt.graphitron.javapoet.MethodSpec findMethod(String name) {
        return GraphitronContextInterfaceGenerator.generate().get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
