package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphitronFacadeGeneratorTest {

    private static GraphitronSchema emptySchema() {
        return new GraphitronSchema(Map.of(), Map.of());
    }

    @Test
    void generate_returnsExactlyOneClassNamedGraphitron() {
        List<TypeSpec> result = GraphitronFacadeGenerator.generate(emptySchema(), "com.example");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("Graphitron");
    }

    @Test
    void generatedClass_isPublicFinal() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0);
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void generatedClass_exposesBuildSchemaBothFactoriesNewGraphQLAndRuntime() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactly("buildSchema", "newExecutionInput", "newOwnedExecutionInput", "newGraphQL", "runtime");
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

    // ===== newExecutionInput =====

    @Test
    void newExecutionInput_emptySchemaCollapsesToSingleDslContextParameter() {
        var newExecutionInput = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(newExecutionInput.parameters()).hasSize(1);
        assertThat(newExecutionInput.parameters().get(0).name()).isEqualTo("defaultDsl");
        assertThat(newExecutionInput.parameters().get(0).type().toString()).isEqualTo("org.jooq.DSLContext");
    }

    @Test
    void newExecutionInput_returnsExecutionInputBuilder() {
        var newExecutionInput = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow();
        assertThat(newExecutionInput.returnType().toString()).isEqualTo("graphql.ExecutionInput.Builder");
        assertThat(newExecutionInput.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void newExecutionInput_bodyPutsDslContextAndSingletonImplOnGraphQLContext() {
        var body = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput"))
            .findFirst()
            .orElseThrow()
            .code()
            .toString();
        // The body null-checks defaultDsl, populates the graphQLContext with the DSLContext.class
        // key and the singleton GraphitronContextImpl under GraphitronContext.class, and attaches
        // a fresh DataLoaderRegistry.
        assertThat(body)
            .contains("java.util.Objects.requireNonNull(defaultDsl")
            .contains("b.put(org.jooq.DSLContext.class, defaultDsl)")
            .contains("com.example.schema.GraphitronContext.GraphitronContextImpl.INSTANCE")
            .contains("new org.dataloader.DataLoaderRegistry()");
    }

    // ===== newOwnedExecutionInput (owned-connection path) =====

    @Test
    void newOwnedExecutionInput_emptySchemaTakesSingleStringClaimsParameter() {
        var owned = ownedFactory(emptySchema());
        assertThat(owned.parameters()).hasSize(1);
        assertThat(owned.parameters().get(0).name()).isEqualTo("claims");
        assertThat(owned.parameters().get(0).type().toString()).isEqualTo("java.lang.String");
        assertThat(owned.returnType().toString()).isEqualTo("graphql.ExecutionInput.Builder");
        assertThat(owned.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
    }

    @Test
    void newOwnedExecutionInput_bodyStashesClaimsUnderTheInstrumentationKey_notADslContext() {
        var body = ownedFactory(emptySchema()).code().toString();
        // The owned path publishes the opaque claims under the instrumentation's CLAIMS_KEY constant
        // (single-sourced with the read site) and the singleton, but never a DSLContext (the
        // instrumentation produces that from the pinned connection).
        assertThat(body)
            .contains("java.util.Objects.requireNonNull(claims")
            .contains("com.example.schema.GraphitronConnectionInstrumentation.CLAIMS_KEY, claims")
            .contains("com.example.schema.GraphitronContext.GraphitronContextImpl.INSTANCE")
            .contains("new org.dataloader.DataLoaderRegistry()")
            .doesNotContain("DSLContext.class");
    }

    private static no.sikt.graphitron.javapoet.MethodSpec ownedFactory(no.sikt.graphitron.rewrite.GraphitronSchema schema) {
        return GraphitronFacadeGenerator.generate(schema, "com.example").get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("newOwnedExecutionInput"))
            .findFirst()
            .orElseThrow();
    }

    // ===== newGraphQL =====

    @Test
    void newGraphQL_isPublicStaticReturningGraphQLBuilder() {
        var method = findFirstMethod("newGraphQL", false);
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(method.returnType().toString()).isEqualTo("graphql.GraphQL.Builder");
        assertThat(method.parameters()).isEmpty();
    }

    @Test
    void newGraphQL_emitsCallerOwnsEverythingNoticeOncePerProcess() {
        // The escape-hatch engine attaches no instrumentation, so the caller owns
        // transactions and identity; newGraphQL() warns that once, guarded by the AtomicBoolean so a
        // rebuilt engine does not re-log it.
        var body = findFirstMethod("newGraphQL", false).code().toString();
        assertThat(body)
            .contains("ESCAPE_HATCH_NOTICE_LOGGED.compareAndSet(false, true)")
            .contains("LOGGER.warn(")
            .contains("escape-hatch engine")
            .contains("owned-connection guarantees do not apply");
    }

    @Test
    void generatedClass_carriesTheLoggerAndEscapeHatchNoticeGuardFields() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example").get(0);
        assertThat(spec.fieldSpecs()).extracting(f -> f.name())
            .contains("LOGGER", "ESCAPE_HATCH_NOTICE_LOGGED");
        var guard = spec.fieldSpecs().stream()
            .filter(f -> f.name().equals("ESCAPE_HATCH_NOTICE_LOGGED"))
            .findFirst().orElseThrow();
        assertThat(guard.type().toString()).isEqualTo("java.util.concurrent.atomic.AtomicBoolean");
    }

    @Test
    void newGraphQL_isPresentExactlyOnceInFederationBuild() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example", true).get(0);
        var newGraphQLCount = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("newGraphQL"))
            .count();
        assertThat(newGraphQLCount).isEqualTo(1);
    }

    // ===== federation overload =====

    @Test
    void nonFederation_exposesSingleBuildSchemaMethod() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example", false).get(0);
        var buildSchemaCount = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .count();
        assertThat(buildSchemaCount).isEqualTo(1);
    }

    @Test
    void federation_exposesTwoBuildSchemaMethods() {
        var spec = GraphitronFacadeGenerator.generate(emptySchema(), "com.example", true).get(0);
        var buildSchemaCount = spec.methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .count();
        assertThat(buildSchemaCount).isEqualTo(2);
    }

    @Test
    void federation_secondBuildSchemaMethod_hasFederationCustomizerParameter() {
        var methods = GraphitronFacadeGenerator.generate(emptySchema(), "com.example", true).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .toList();
        var twoArg = methods.get(1);
        assertThat(twoArg.parameters()).hasSize(2);
        assertThat(twoArg.parameters().get(1).type().toString())
            .isEqualTo("java.util.function.Consumer<com.apollographql.federation.graphqljava.SchemaTransformer>");
    }

    @Test
    void federation_secondBuildSchemaMethod_delegatesToGraphitronSchemaBuildTwoArg() {
        var methods = GraphitronFacadeGenerator.generate(emptySchema(), "com.example", true).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals("buildSchema"))
            .toList();
        var body = methods.get(1).code().toString();
        assertThat(body).contains(
            "return com.example.schema.GraphitronSchema.build(schemaCustomizer, federationCustomizer)");
    }

    private static no.sikt.graphitron.javapoet.MethodSpec findFirstMethod(String name, boolean federation) {
        return GraphitronFacadeGenerator.generate(emptySchema(), "com.example", federation).get(0).methodSpecs().stream()
            .filter(m -> m.name().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
