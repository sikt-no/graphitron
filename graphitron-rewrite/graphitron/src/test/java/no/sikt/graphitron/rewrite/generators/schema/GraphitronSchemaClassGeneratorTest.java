package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;

@UnitTier
class GraphitronSchemaClassGeneratorTest {

    private static final String OUTPUT_PKG = "com.example";

    @Test
    void generate_returnsExactlyOneClassNamedGraphitronSchema() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        List<TypeSpec> result = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG);
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
        var bundle = TestSchemaHelper.buildBundle("""
            directive @auth(roles: [String!]) on FIELD_DEFINITION
            type Query { secret: String @auth(roles: ["admin"]) }
            """);
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0)
            .methodSpecs().get(0).code().toString();
        assertThat(body)
            .contains(".additionalDirective(")
            .contains(".name(\"auth\")");
    }

    @Test
    void build_skipsAdditionalDirective_forGeneratorOnlyDirectives() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0)
            .methodSpecs().get(0).code().toString();
        assertThat(body).doesNotContain(".name(\"table\")");
        assertThat(body).doesNotContain(".name(\"field\")");
        assertThat(body).doesNotContain(".name(\"condition\")");
    }

    @Test
    void build_callsRegisterFetchersForEachTypeWithFetchers_inAlphabeticalOrder() {
        var bundle = TestSchemaHelper.buildBundle("""
            type Query { x: String }
            type Film { id: ID! }
            type Person { id: ID! }
            """);
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled(), Set.of("Film", "Person", "Query"), OUTPUT_PKG)
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
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled(), Set.of("Query"), OUTPUT_PKG)
            .get(0).methodSpecs().get(0).code().toString();
        int registerIdx = body.indexOf("registerFetchers(codeRegistry)");
        int schemaBuilderIdx = body.indexOf("schemaBuilder = graphql.schema.GraphQLSchema.newSchema()");
        assertThat(registerIdx).isGreaterThan(0).isLessThan(schemaBuilderIdx);
    }

    // ===== @asConnection synthesis =====

    @Test
    void build_emitsAdditionalType_forSynthesisedConnection() {
        var body = buildBody("type Query { films: [Film!]! @asConnection }\ntype Film { id: ID! }");
        assertThat(body).contains(".additionalType(com.example.schema.QueryFilmsConnectionType.type())");
    }

    @Test
    void build_emitsAdditionalType_forSynthesisedEdge() {
        var body = buildBody("type Query { films: [Film!]! @asConnection }\ntype Film { id: ID! }");
        assertThat(body).contains(".additionalType(com.example.schema.QueryFilmsEdgeType.type())");
    }

    @Test
    void build_emitsAdditionalType_forSynthesisedPageInfo_whenAbsent() {
        var body = buildBody("type Query { films: [Film!]! @asConnection }\ntype Film { id: ID! }");
        assertThat(body).contains(".additionalType(com.example.schema.PageInfoType.type())");
    }

    @Test
    void build_doesNotAddPageInfoTwice_whenAlreadyDeclared() {
        // When PageInfo is declared in the schema it is registered via the regular
        // additionalTypeNames loop. The synthesis path must not add it a second time.
        var body = buildBody("""
            type Query { films: [Film!]! @asConnection }
            type Film { id: ID! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            """);
        int count = countOccurrences(body, "PageInfoType.type()");
        assertThat(count).as("PageInfoType.type() should appear exactly once (from regular type loop only)").isEqualTo(1);
    }

    @Test
    void build_doesNotEmitAdditionalType_forStructuralConnection() {
        // A hand-written Connection type already in the assembled schema should appear via
        // the regular additionalTypeNames loop (not the synthesis path), so no duplicate.
        var body = buildBody("""
            type Query { films: FilmsConnection! }
            type FilmsConnection { edges: [FilmsEdge!]! nodes: [Film!]! pageInfo: PageInfo! }
            type FilmsEdge { cursor: String! node: Film! }
            type PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }
            type Film { id: ID! }
            """);
        // FilmsConnectionType appears once (from regular additionalTypeNames), not from synthesis
        int count = countOccurrences(body, "FilmsConnectionType.type()");
        assertThat(count).as("FilmsConnectionType.type() should appear exactly once").isEqualTo(1);
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    @Test
    void planFor_preservesRootAndAlphabeticalOrder() {
        var bundle = TestSchemaHelper.buildBundle("""
            type Query { x: String }
            type Mutation { y: String }
            type Zebra { id: ID! }
            type Alpha { id: ID! }
            """);
        var plan = GraphitronSchemaClassGenerator.planFor(bundle.model(), bundle.assembled());
        assertThat(plan.hasQuery()).isTrue();
        assertThat(plan.hasMutation()).isTrue();
        assertThat(plan.hasSubscription()).isFalse();
        assertThat(plan.additionalTypeNames()).containsSubsequence("Alpha", "Zebra");
    }

    // ===== TableInterfaceType TypeResolver emission =====

    private static final String INTERFACE_SDL = """
        type Query { allContent: [Content!]! }
        interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
            id: ID!
        }
        type Film implements Content @table(name: "content") @discriminator(value: "FILM") {
            id: ID!
            title: String
        }
        type Short implements Content @table(name: "content") @discriminator(value: "SHORT") {
            id: ID!
            duration: Int
        }
        """;

    @Test
    void build_emitsTypeResolver_forTableInterfaceType() {
        // Intentional body-content assertion: the TypeResolver is runtime dispatch infrastructure
        // — no structural equivalent. A missing typeResolver call means graphql-java cannot route
        // fetched Records to their concrete GraphQL type, causing all interface queries to fail.
        var body = buildBody(INTERFACE_SDL);
        assertThat(body).contains("codeRegistry.typeResolver(\"Content\"");
    }

    @Test
    void build_typeResolver_mapsEachDiscriminatorValueToConcreteType() {
        var body = buildBody(INTERFACE_SDL);
        assertThat(body).contains("case \"FILM\"");
        assertThat(body).contains("getObjectType(\"Film\")");
        assertThat(body).contains("case \"SHORT\"");
        assertThat(body).contains("getObjectType(\"Short\")");
    }

    @Test
    void build_typeResolver_readsDiscriminatorColumnFromRecord() {
        // The discriminator column is resolved to its SQL name (lowercase) at classification
        // time so DSL.name(...) generates a correctly-cased quoted identifier for PostgreSQL.
        var body = buildBody(INTERFACE_SDL);
        assertThat(body).contains("\"content_type\"");
    }

    @Test
    void build_typeResolver_skipsInterfaceWithNoDiscriminatorValues() {
        // An interface with @discriminate but no concrete types with @discriminator
        // must not produce a typeResolver call (there's nothing to switch on).
        var body = buildBody("""
            type Query { x: Content }
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
                id: ID!
            }
            type Film implements Content @table(name: "content") {
                id: ID!
            }
            """);
        assertThat(body).doesNotContain("codeRegistry.typeResolver(\"Content\"");
    }

    @Test
    void build_typeResolverId_alphabeticalOrder_beforeSchemaBuilder() {
        // TypeResolvers for multiple interfaces are registered in alphabetical order and
        // always before .query/.additionalType so the code registry is fully populated before build.
        // Uses real fixture tables (content, film) so TableInterfaceType classification succeeds.
        var body = buildBody("""
            type Query { z: ZebraContent  a: AlphaContent }
            interface ZebraContent @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
                id: ID!
            }
            type ZebraFilm implements ZebraContent @table(name: "content") @discriminator(value: "FILM") { id: ID! }
            interface AlphaContent @table(name: "film") @discriminate(on: "FILM_TYPE") {
                id: ID!
            }
            type AlphaA implements AlphaContent @table(name: "film") @discriminator(value: "REGULAR") { id: ID! }
            """);
        int zebraIdx = body.indexOf("codeRegistry.typeResolver(\"ZebraContent\"");
        int alphaIdx = body.indexOf("codeRegistry.typeResolver(\"AlphaContent\"");
        int queryIdx = body.indexOf(".query(");
        assertThat(alphaIdx).as("AlphaContent resolver before ZebraContent (alphabetical)").isLessThan(zebraIdx);
        assertThat(zebraIdx).as("typeResolvers before .query()").isLessThan(queryIdx);
    }

    // ===== federation overload =====

    @Test
    void nonFederation_emitsSingleBuildMethod() {
        var spec = generate("type Query { x: String }");
        assertThat(spec.methodSpecs()).extracting(m -> m.name()).containsExactly("build");
    }

    @Test
    void federation_emitsTwoMethodsWhenFederationLinkTrue() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var spec = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0);
        assertThat(spec.methodSpecs()).extracting(m -> m.name())
            .containsExactly("build", "build");
    }

    @Test
    void federation_oneArgMethodDelegatesToTwoArgForm() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var methods = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0).methodSpecs();
        var oneArg = methods.get(0);
        assertThat(oneArg.parameters()).hasSize(1);
        assertThat(oneArg.code().toString()).contains("return build(customizer, fed -> {})");
    }

    @Test
    void federation_twoArgBodyCallsFederationTransform() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var methods = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0).methodSpecs();
        var twoArg = methods.get(1);
        assertThat(twoArg.parameters()).hasSize(2);
        var body = twoArg.code().toString();
        assertThat(body).contains("Federation.transform(base)");
        assertThat(body).contains(".setFederation2(true)");
        assertThat(body).contains(".resolveEntityType(");
        assertThat(body).contains(".fetchEntities(");
    }

    @Test
    void federation_twoArgBodyInvokesFederationCustomizerBeforeBuild() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var body = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0)
            .methodSpecs().get(1).code().toString();
        int customizerIdx = body.indexOf("federationCustomizer.accept(fb)");
        int buildIdx = body.indexOf("return fb.build()");
        assertThat(customizerIdx).isGreaterThan(0).isLessThan(buildIdx);
    }

    private static TypeSpec generate(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG).get(0);
    }

    private static String buildBody(String sdl) {
        return generate(sdl).methodSpecs().get(0).code().toString();
    }
}
