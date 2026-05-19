package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphitronFacadeGeneratorTest {

    @Test
    void generate_returnsExactlyOneClassNamedGraphitron() {
        List<TypeSpec> result = GraphitronFacadeGenerator.generate("com.example");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Graphitron");
    }

    @Test
    void generatedClass_isPublicFinal() {
        var spec = GraphitronFacadeGenerator.generate("com.example").get(0);
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void generatedClass_exposesBuildSchemaAndNewExecutionInputMethods() {
        var spec = GraphitronFacadeGenerator.generate("com.example").get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactly("buildSchema", "newExecutionInput", "newExecutionInput");
    }

    @Test
    void buildSchema_isPublicStaticReturningGraphQLSchema() {
        var method = findFirstMethod("buildSchema", false);
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLSchema");
    }

    @Test
    void buildSchema_takesConsumerOfGraphQLSchemaBuilderNamedCustomizer() {
        var method = findFirstMethod("buildSchema", false);
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).name()).isEqualTo("customizer");
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("java.util.function.Consumer<graphql.schema.GraphQLSchema.Builder>");
    }

    @Test
    void buildSchema_delegatesToGraphitronSchemaBuild() {
        var body = findFirstMethod("buildSchema", false).code().toString();
        assertThat(body).contains("return com.example.schema.GraphitronSchema.build(customizer)");
    }

    @Test
    void buildSchema_javadocDocumentsTheAdditiveOnlyContract() {
        var javadoc = findFirstMethod("buildSchema", false).javadoc().toString();
        assertThat(javadoc)
            .contains("Use additive methods only")
            .contains(".query()")
            .contains(".mutation()")
            .contains(".subscription()")
            .contains("clearDirectives()")
            .contains(".codeRegistry(GraphQLCodeRegistry)")
            .contains("UnaryOperator");
    }

    // ===== newExecutionInput factories =====

    @Test
    void newExecutionInput_hasGraphitronContextAndDSLContextOverloads() {
        var newExecutionInputs = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .toList();
        assertThat(newExecutionInputs).hasSize(2);
        var paramTypes = newExecutionInputs.stream()
            .map(m -> m.parameters().get(0).type().toString())
            .toList();
        assertThat(paramTypes).containsExactlyInAnyOrder(
            "com.example.schema.GraphitronContext",
            "org.jooq.DSLContext");
    }

    @Test
    void newExecutionInput_bothOverloadsReturnExecutionInputBuilder() {
        var newExecutionInputs = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .toList();
        assertThat(newExecutionInputs).hasSize(2);
        for (var method : newExecutionInputs) {
            assertThat(method.returnType().toString()).isEqualTo("graphql.ExecutionInput.Builder");
            assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        }
    }

    // ===== federation overload =====

    @Test
    void nonFederation_exposesSingleBuildSchemaMethod() {
        var spec = GraphitronFacadeGenerator.generate("com.example", false).get(0);
        var buildSchemaCount = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .count();
        assertThat(buildSchemaCount).isEqualTo(1);
    }

    @Test
    void federation_exposesTwoBuildSchemaMethods() {
        var spec = GraphitronFacadeGenerator.generate("com.example", true).get(0);
        var buildSchemaCount = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .count();
        assertThat(buildSchemaCount).isEqualTo(2);
    }

    @Test
    void federation_secondBuildSchemaMethod_hasFederationCustomizerParameter() {
        var methods = GraphitronFacadeGenerator.generate("com.example", true).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .toList();
        var twoArg = methods.get(1);
        assertThat(twoArg.parameters()).hasSize(2);
        assertThat(twoArg.parameters().get(1).type().toString())
            .isEqualTo("java.util.function.Consumer<com.apollographql.federation.graphqljava.SchemaTransformer>");
    }

    @Test
    void federation_secondBuildSchemaMethod_delegatesToGraphitronSchemaBuildTwoArg() {
        var methods = GraphitronFacadeGenerator.generate("com.example", true).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .toList();
        var body = methods.get(1).code().toString();
        assertThat(body).contains(
            "return com.example.schema.GraphitronSchema.build(schemaCustomizer, federationCustomizer)");
    }

    private static no.sikt.graphitron.javapoet.MethodSpec findFirstMethod(String name, boolean federation) {
        return GraphitronFacadeGenerator.generate("com.example", federation).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
