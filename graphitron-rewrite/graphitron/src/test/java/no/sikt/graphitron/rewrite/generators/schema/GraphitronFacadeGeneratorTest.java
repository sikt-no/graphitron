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
    void generatedClass_exposesSingleBuildSchemaMethod() {
        var spec = GraphitronFacadeGenerator.generate("com.example").get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("buildSchema");
    }

    @Test
    void buildSchema_isPublicStaticReturningGraphQLSchema() {
        var method = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().get(0);
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLSchema");
    }

    @Test
    void buildSchema_takesConsumerOfGraphQLSchemaBuilderNamedCustomizer() {
        var method = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().get(0);
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).name()).isEqualTo("customizer");
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("java.util.function.Consumer<graphql.schema.GraphQLSchema.Builder>");
    }

    @Test
    void buildSchema_delegatesToGraphitronSchemaBuild() {
        var body = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().get(0).code().toString();
        assertThat(body).contains("return com.example.schema.GraphitronSchema.build(customizer)");
    }

    @Test
    void buildSchema_javadocDocumentsTheAdditiveOnlyContract() {
        var javadoc = GraphitronFacadeGenerator.generate("com.example").get(0).methodSpecs().get(0).javadoc().toString();
        assertThat(javadoc)
            .contains("Use additive methods only")
            .contains(".query()")
            .contains(".mutation()")
            .contains(".subscription()")
            .contains("clearDirectives()")
            .contains(".codeRegistry(GraphQLCodeRegistry)")
            .contains("UnaryOperator");
    }

    // ===== federation overload =====

    @Test
    void nonFederation_exposesSingleBuildSchemaMethod() {
        var spec = GraphitronFacadeGenerator.generate("com.example", false).get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("buildSchema");
    }

    @Test
    void federation_exposesTwoBuildSchemaMethods() {
        var spec = GraphitronFacadeGenerator.generate("com.example", true).get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactly("buildSchema", "buildSchema");
    }

    @Test
    void federation_secondBuildSchemaMethod_hasFederationCustomizerParameter() {
        var methods = GraphitronFacadeGenerator.generate("com.example", true).get(0).methodSpecs();
        var twoArg = methods.get(1);
        assertThat(twoArg.parameters()).hasSize(2);
        assertThat(twoArg.parameters().get(1).type().toString())
            .isEqualTo("java.util.function.Consumer<com.apollographql.federation.graphqljava.SchemaTransformer>");
    }

    @Test
    void federation_secondBuildSchemaMethod_delegatesToGraphitronSchemaBuildTwoArg() {
        var methods = GraphitronFacadeGenerator.generate("com.example", true).get(0).methodSpecs();
        var body = methods.get(1).code().toString();
        assertThat(body).contains(
            "return com.example.schema.GraphitronSchema.build(schemaCustomizer, federationCustomizer)");
    }
}
