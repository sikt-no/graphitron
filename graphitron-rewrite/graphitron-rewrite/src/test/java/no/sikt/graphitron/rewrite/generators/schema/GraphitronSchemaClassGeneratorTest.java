package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphitronSchemaClassGeneratorTest {

    private static final String OUTPUT_PKG = "com.example";

    @Test
    void generate_returnsExactlyOneClassNamedGraphitronSchema() {
        var schema = TestSchemaHelper.buildBundle("type Query { x: String }").assembled();
        List<TypeSpec> result = GraphitronSchemaClassGenerator.generate(schema, Set.of(), OUTPUT_PKG);
        assertThat(result).hasSize(1);
        var spec = result.get(0);
        assertThat(spec.name()).isEqualTo("GraphitronSchema");
        assertThat(spec.modifiers()).contains(Modifier.PUBLIC, Modifier.FINAL);
    }

    @Test
    void build_methodIsPublicStaticReturningGraphQLSchema_withCustomizerParameter() {
        var spec = generate("type Query { x: String }");
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("build");
        var method = spec.methodSpecs().get(0);
        assertThat(method.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(method.returnType().toString()).isEqualTo("graphql.schema.GraphQLSchema");
        assertThat(method.parameters()).hasSize(1);
        assertThat(method.parameters().get(0).name()).isEqualTo("customizer");
        assertThat(method.parameters().get(0).type().toString())
            .isEqualTo("java.util.function.Consumer<graphql.schema.GraphQLSchema.Builder>");
    }

    @Test
    void build_routesQueryThroughQueryEntryPoint() {
        var body = buildBody("type Query { x: String }");
        assertThat(body).contains(".query(com.example.schema.QueryType.type())");
    }

    @Test
    void build_routesMutationThroughMutationEntryPoint() {
        var body = buildBody("""
            type Query { x: String }
            type Mutation { createFilm: String }
            """);
        assertThat(body).contains(".mutation(com.example.schema.MutationType.type())");
    }

    @Test
    void build_routesSubscriptionThroughSubscriptionEntryPoint() {
        var body = buildBody("""
            schema { query: Query, subscription: Subscription }
            type Query { x: String }
            type Subscription { counter: Int }
            """);
        assertThat(body).contains(".subscription(com.example.schema.SubscriptionType.type())");
    }

    @Test
    void build_registersNonRootTypesViaAdditionalType() {
        var body = buildBody("""
            type Query { film: Film }
            type Film { id: ID! }
            """);
        assertThat(body).contains(".additionalType(com.example.schema.FilmType.type())");
    }

    @Test
    void build_includesEnumsAndInputsAsAdditionalTypes() {
        var body = buildBody("""
            type Query { x: String }
            enum Status { A B }
            input FilterInput { q: String }
            """);
        assertThat(body).contains(".additionalType(com.example.schema.StatusType.type())");
        assertThat(body).contains(".additionalType(com.example.schema.FilterInputType.type())");
    }

    @Test
    void build_skipsDirectiveSupportInputTypes() {
        var body = buildBody("type Query { x: String }");
        InputDirectiveInputTypes.NAMES.forEach(name ->
            assertThat(body)
                .as("internal directive input %s must not be added", name)
                .doesNotContain(name + "Type.type()"));
    }

    @Test
    void build_attachesCodeRegistryAndInvokesCustomizerBeforeBuild() {
        var body = buildBody("type Query { x: String }");
        assertThat(body).contains("graphql.schema.GraphQLCodeRegistry.Builder codeRegistry = graphql.schema.GraphQLCodeRegistry.newCodeRegistry()");
        assertThat(body).contains(".codeRegistry(codeRegistry.build())");
        int customizerIdx = body.indexOf("customizer.accept(schemaBuilder)");
        int buildIdx = body.indexOf("return schemaBuilder.build()");
        assertThat(customizerIdx).isGreaterThan(0).isLessThan(buildIdx);
    }

    @Test
    void build_emitsAdditionalDirective_forSurvivorDirectiveDefinitions() {
        var schema = TestSchemaHelper.buildBundle("""
            directive @auth(roles: [String!]) on FIELD_DEFINITION
            type Query { secret: String @auth(roles: ["admin"]) }
            """).assembled();
        var body = GraphitronSchemaClassGenerator.generate(schema).get(0)
            .methodSpecs().get(0).code().toString();
        assertThat(body)
            .contains(".additionalDirective(")
            .contains(".name(\"auth\")");
    }

    @Test
    void build_skipsAdditionalDirective_forGeneratorOnlyDirectives() {
        var schema = TestSchemaHelper.buildBundle("type Query { x: String }").assembled();
        var body = GraphitronSchemaClassGenerator.generate(schema).get(0)
            .methodSpecs().get(0).code().toString();
        assertThat(body).doesNotContain(".name(\"table\")");
        assertThat(body).doesNotContain(".name(\"field\")");
        assertThat(body).doesNotContain(".name(\"condition\")");
    }

    @Test
    void build_callsRegisterFetchersForEachTypeWithFetchers_inAlphabeticalOrder() {
        var schema = TestSchemaHelper.buildBundle("""
            type Query { x: String }
            type Film { id: ID! }
            type Person { id: ID! }
            """).assembled();
        var body = GraphitronSchemaClassGenerator.generate(schema, Set.of("Film", "Person", "Query"), OUTPUT_PKG)
            .get(0).methodSpecs().get(0).code().toString();
        assertThat(body).contains("com.example.schema.FilmType.registerFetchers(codeRegistry)");
        assertThat(body).contains("com.example.schema.PersonType.registerFetchers(codeRegistry)");
        assertThat(body).contains("com.example.schema.QueryType.registerFetchers(codeRegistry)");
        int filmIdx = body.indexOf("FilmType.registerFetchers");
        int personIdx = body.indexOf("PersonType.registerFetchers");
        int queryIdx = body.indexOf("QueryType.registerFetchers");
        assertThat(filmIdx).isLessThan(personIdx);
        assertThat(personIdx).isLessThan(queryIdx);
    }

    @Test
    void build_callsRegisterFetchersBeforeAnySchemaBuilderSetup() {
        var schema = TestSchemaHelper.buildBundle("type Query { x: String }").assembled();
        var body = GraphitronSchemaClassGenerator.generate(schema, Set.of("Query"), OUTPUT_PKG)
            .get(0).methodSpecs().get(0).code().toString();
        int registerIdx = body.indexOf("registerFetchers(codeRegistry)");
        int schemaBuilderIdx = body.indexOf("schemaBuilder = graphql.schema.GraphQLSchema.newSchema()");
        assertThat(registerIdx).isGreaterThan(0).isLessThan(schemaBuilderIdx);
    }

    @Test
    void planFor_preservesRootAndAlphabeticalOrder() {
        var schema = TestSchemaHelper.buildBundle("""
            type Query { x: String }
            type Mutation { y: String }
            type Zebra { id: ID! }
            type Alpha { id: ID! }
            """).assembled();
        var plan = GraphitronSchemaClassGenerator.planFor(schema);
        assertThat(plan.hasQuery()).isTrue();
        assertThat(plan.hasMutation()).isTrue();
        assertThat(plan.hasSubscription()).isFalse();
        assertThat(plan.additionalTypeNames()).containsSubsequence("Alpha", "Zebra");
    }

    private static TypeSpec generate(String sdl) {
        var schema = TestSchemaHelper.buildBundle(sdl).assembled();
        return GraphitronSchemaClassGenerator.generate(schema, Set.of(), OUTPUT_PKG).get(0);
    }

    private static String buildBody(String sdl) {
        return generate(sdl).methodSpecs().get(0).code().toString();
    }
}
