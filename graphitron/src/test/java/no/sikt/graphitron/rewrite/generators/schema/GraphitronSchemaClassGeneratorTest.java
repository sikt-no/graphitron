package no.sikt.graphitron.rewrite.generators.schema;

import no.sikt.graphitron.javapoet.MethodSpec;
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
        var build = publicBuild(spec);
        assertThat(build.modifiers()).contains(Modifier.PUBLIC, Modifier.STATIC);
        assertThat(build.returnType().toString()).isEqualTo("graphql.schema.GraphQLSchema");
        assertThat(build.parameters()).hasSize(1);
        assertThat(build.parameters().get(0).name()).isEqualTo("customizer");
        assertThat(build.parameters().get(0).type().toString())
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
            type Film @table(name: "film") { id: ID! }
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
    void build_skipsAllDirectiveSupportTypesWhenUnreferenced() {
        var body = buildBody("type Query { x: String }");
        no.sikt.graphitron.rewrite.schema.DirectiveSupportTypes.all().forEach(name ->
            assertThat(body)
                .as("support type %s must not be added when no consumer coordinate references it", name)
                .doesNotContain(name + "Type.type()"));
    }

    @Test
    void build_registersPublishedSupportTypeWhenReferenced() {
        var body = buildBody("""
            type Query { films(order: FilmOrderBy): String }
            input FilmOrderBy { direction: SortDirection }
            """);
        assertThat(body).contains(".additionalType(com.example.schema.SortDirectionType.type())");
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
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0).toString();
        assertThat(body)
            .contains(".additionalDirective(")
            .contains(".name(\"auth\")");
    }

    /**
 * An {@code extend schema @link(...)} declaration on the consumer SDL
     * must reach the runtime build via {@code .withSchemaAppliedDirectives(...)}.
     * Without this, the runtime {@code _service.sdl} (and any printer output)
     * lacks the {@code schema @link(...)} block, which makes federation
     * supergraph composition fall through to Fed1 and reject the Fed2-shaped
     * {@code @key} declarations.
     */
    @Test
    void build_emitsWithSchemaAppliedDirectives_forSchemaLevelLink() {
        var bundle = TestSchemaHelper.buildBundle("""
            directive @link(url: String!, import: [String!]) repeatable on SCHEMA
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            type Query { x: String }
            """);
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0).toString();
        assertThat(body)
            .contains(".withSchemaAppliedDirectives(java.util.List.of(")
            .contains("graphql.schema.GraphQLAppliedDirective.newDirective()")
            .contains(".name(\"link\")")
            .contains("https://specs.apollo.dev/federation/v2.10");
        // Must be emitted before .codeRegistry(...) so the schema-level directive
        // ships on the final GraphQLSchema, not after the build seam. Look for the
        // call sites inside the build method.
        String buildBody = publicBuild(generate("""
            directive @link(url: String!, import: [String!]) repeatable on SCHEMA
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
            type Query { x: String }
            """)).code().toString();
        int appliedIdx = buildBody.indexOf(".withSchemaAppliedDirectives(");
        int registryIdx = buildBody.indexOf(".codeRegistry(codeRegistry.build())");
        assertThat(appliedIdx).isGreaterThan(0).isLessThan(registryIdx);
    }

    @Test
    void build_skipsWithSchemaAppliedDirectives_whenNoSchemaLevelSurvivors() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0).toString();
        assertThat(body).doesNotContain(".withSchemaAppliedDirectives(");
    }

    @Test
    void build_skipsAdditionalDirective_forGeneratorOnlyDirectives() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var body = GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled()).get(0).toString();
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
            .get(0).toString();
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
            .get(0).toString();
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
            type Query { zebra: Zebra alpha: Alpha }
            type Mutation { y: String }
            type Zebra @table(name: "film") { id: ID! }
            type Alpha @table(name: "actor") { id: ID! }
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
    void build_typeResolver_routesOffSyntheticDiscriminatorAlias() {
        // The TypeResolver routes off the synthetic discriminator alias the interface fetcher
        // projects (MultiTablePolymorphicEmitter.DISCRIMINATOR_COLUMN), not the raw discriminator column
        // name. When the interface exposes the discriminator as a queryable field, the real column is
        // also projected by the participant $fields, and a bare read of the column name matches both
        // projections ambiguously. The alias is distinct from any real column, so the routing read is
        // unambiguous; the raw column name no longer appears in the resolver.
        var body = buildBody(INTERFACE_SDL);
        assertThat(body).contains("\"__discriminator__\"");
        assertThat(body).doesNotContain("\"content_type\"");
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

    // ===== plain InterfaceType / UnionType TypeResolver emission (Track B1) =====

    private static final String PLAIN_INTERFACE_SDL = """
        type Query { search: [Searchable!]! }
        interface Searchable { id: ID! }
        type Film implements Searchable @table(name: "film") { id: ID! }
        type Actor implements Searchable @table(name: "actor") { id: ID! }
        """;

    @Test
    void build_emitsTypeResolver_forPlainInterfaceType() {
        var body = buildBody(PLAIN_INTERFACE_SDL);
        assertThat(body).contains("codeRegistry.typeResolver(\"Searchable\"");
    }

    @Test
    void build_typeResolverForPlainInterface_readsTypenameViaDslField() {
        // Reads the synthetic __typename column Track B's stage-1 emitter projects on every
        // branch. DSL.field(DSL.name("__typename")) is the typed read used elsewhere in the
        // generated code; the bare-string overload would also work but the typed form is what
        // the plan specifies and what stage 2's typed Records carry forward.
        var body = buildBody(PLAIN_INTERFACE_SDL);
        assertThat(body).contains("\"__typename\"");
        assertThat(body).contains("env.getSchema().getObjectType(typeName)");
    }

    @Test
    void build_emitsTypeResolver_forUnionType() {
        var body = buildBody("""
            type Query { document: Document }
            union Document = Film | Actor
            type Film @table(name: "film") { id: ID! }
            type Actor @table(name: "actor") { id: ID! }
            """);
        assertThat(body).contains("codeRegistry.typeResolver(\"Document\"");
    }

    @Test
    void build_doesNotDoubleRegisterNodeInterface_whenNodeTypesPresent() {
        // When the schema has a NodeType, GraphitronSchemaClassGenerator delegates Node-resolver
        // registration to QueryNodeFetcher.registerTypeResolver(codeRegistry); the actual
        // codeRegistry.typeResolver("Node", ...) call lives in QueryNodeFetcher's own emitted
        // class, not inline in GraphitronSchema.build(). The plain-interface path must skip
        // "Node" so it doesn't emit a second, inline registration that would conflict with
        // QueryNodeFetcher's; graphql-java's GraphQLCodeRegistry.Builder rejects a duplicate
        // typeResolver for the same type name with an IllegalArgumentException.
        var body = buildBody("""
            type Query { x: String }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) { id: ID! @nodeId }
            """);
        assertThat(body)
            .as("delegates Node resolver registration to QueryNodeFetcher")
            .contains("QueryNodeFetcher.registerTypeResolver(codeRegistry)");
        assertThat(body)
            .as("does not emit a second inline codeRegistry.typeResolver(\"Node\", ...) call")
            .doesNotContain("codeRegistry.typeResolver(\"Node\"");
    }

    @Test
    void build_skipsNodeInterface_whenNoNodeTypePresent() {
        // TestSchemaHelper injects `interface Node { id: ID! }` into every test SDL. Without a
        // NodeType, QueryNodeFetcher's resolver registration is suppressed; the plain-interface
        // path also skips "Node" so the orphan declaration doesn't end up with a resolver
        // pointing at no concrete types.
        var body = buildBody("type Query { x: String }");
        assertThat(body).doesNotContain("codeRegistry.typeResolver(\"Node\"");
    }

    @Test
    void build_typeResolverForPlainInterfaces_alphabeticalOrder_beforeSchemaBuilder() {
        // Resolver registration order is deterministic (sorted by type name) and lands before
        // .query()/.additionalType() so the code registry is fully populated before build.
        var body = buildBody("""
            type Query { a: AlphaInterface, z: ZebraInterface }
            interface AlphaInterface { id: ID! }
            interface ZebraInterface { id: ID! }
            type AlphaImpl implements AlphaInterface @table(name: "film") { id: ID! }
            type ZebraImpl implements ZebraInterface @table(name: "actor") { id: ID! }
            """);
        int alphaIdx = body.indexOf("codeRegistry.typeResolver(\"AlphaInterface\"");
        int zebraIdx = body.indexOf("codeRegistry.typeResolver(\"ZebraInterface\"");
        int queryIdx = body.indexOf(".query(");
        assertThat(alphaIdx).as("AlphaInterface resolver before ZebraInterface (alphabetical)")
            .isGreaterThanOrEqualTo(0).isLessThan(zebraIdx);
        assertThat(zebraIdx).as("typeResolvers before .query()").isLessThan(queryIdx);
    }

    // ===== @error-only union/interface TypeResolver emission =====


    private static final String ERROR_UNION_SDL = """
        type ValidationErr @error(handlers: [{handler: VALIDATION}]) {
            path: [String!]!
            message: String!
        }
        type DbErr @error(handlers: [{handler: DATABASE, sqlState: "23503"}]) {
            path: [String!]!
            message: String!
        }
        union SakError = ValidationErr | DbErr
        type SakPayload {
            data: String
            errors: [SakError]
        }
        type Query { sak: SakPayload @service(service: {className: "no.sikt.graphitron.rewrite.TestServiceStub", method: "runSak"}) }
        """;

    @Test
    void build_emitsErrorTypeResolver_forUnionOfErrorTypes() {
        // An @error-only union gets a TypeResolver that dispatches by source-class
        // instanceof; the runtime sources are throwables (GENERIC/DATABASE) or GraphQLErrors
        // (VALIDATION), never jOOQ records. The TypeResolver must therefore NOT emit the
        // jOOQ-record __typename probe used for table-backed unions.
        var body = buildBody(ERROR_UNION_SDL);
        assertThat(body).contains("codeRegistry.typeResolver(\"SakError\"");
        // Source-class dispatch references graphql.GraphQLError and java.sql.SQLException.
        assertThat(body).contains("graphql.GraphQLError");
        assertThat(body).contains("java.sql.SQLException");
        assertThat(body).contains("\"23503\".equals(sqlEx.getSQLState())");
        // Routes each source to the right SDL @error type.
        assertThat(body).contains("env.getSchema().getObjectType(\"ValidationErr\")");
        assertThat(body).contains("env.getSchema().getObjectType(\"DbErr\")");
    }

    @Test
    void build_errorTypeResolver_doesNotEmitJooqRecordProbe() {
        // Belt-and-braces: the @error TypeResolver path must skip the
        // (org.jooq.Record) cast + DSL.field("__typename") probe used by table-backed unions.
        // A SakError TypeResolver that did the cast would CCE at runtime since the source is
        // a GraphQLError or Throwable.
        var body = buildBody(ERROR_UNION_SDL);
        int sakErrorBlockStart = body.indexOf("codeRegistry.typeResolver(\"SakError\"");
        assertThat(sakErrorBlockStart).isGreaterThanOrEqualTo(0);
        int sakErrorBlockEnd = body.indexOf("});", sakErrorBlockStart);
        String block = body.substring(sakErrorBlockStart, sakErrorBlockEnd);
        assertThat(block).doesNotContain("org.jooq.Record");
        assertThat(block).doesNotContain("__typename");
    }

    @Test
    void build_emitsErrorTypeFieldFetchers_forPathAndMessage() {
        // Every @error type gets path/message DataFetchers; path is synthesized
        // from the GraphQL execution context for non-GraphQLError sources (Throwable has no
        // getPath()) so the schema's [String!]! contract holds for GENERIC/DATABASE handlers.
        var body = buildBody(ERROR_UNION_SDL);
        assertThat(body).contains("FieldCoordinates.coordinates(\"ValidationErr\", \"path\")");
        assertThat(body).contains("FieldCoordinates.coordinates(\"ValidationErr\", \"message\")");
        assertThat(body).contains("FieldCoordinates.coordinates(\"DbErr\", \"path\")");
        assertThat(body).contains("FieldCoordinates.coordinates(\"DbErr\", \"message\")");
    }

    @Test
    void build_errorTypeFieldFetchers_pathSynthesisRoutesGraphQLErrorThroughGetPath() {
        // The path read is reified onto <ErrorType>Fetchers. GraphQLError sources route
        // through GraphQLError.getPath() (preserving per-violation paths recorded by
        // ConstraintViolations.toGraphQLError); Throwable sources fall back to
        // env.getExecutionStepInfo().getPath().toList() so the SDL field's [String!]! non-null
        // contract holds.
        var fetchers = errorFetcherSource(ERROR_UNION_SDL, "ValidationErr");
        assertThat(fetchers).contains("ge.getPath()");
        assertThat(fetchers).contains("env.getExecutionStepInfo().getPath().toList()");
    }

    @Test
    void build_errorTypeFieldFetchers_wiringIsMethodReferenceIntoReifiedClass() {
        // The schema build() body wires <ErrorType>Fetchers::path /::message (method
        // references, no DataFetcher<Object> cast lambda); the reads themselves are named methods
        // on the reified class that read the source via DataFetchingEnvironment.getSource() (the
        // canonical accessor in graphql-java 25; getObject() exists only on TypeResolutionEnvironment).
        var body = buildBody(ERROR_UNION_SDL);
        assertThat(body).contains("ValidationErrFetchers::path");
        assertThat(body).contains("ValidationErrFetchers::message");
        assertThat(body).doesNotContain("(graphql.schema.DataFetcher<java.lang.Object>) env -> {");

        var fetchers = errorFetcherSource(ERROR_UNION_SDL, "ValidationErr");
        assertThat(fetchers).contains("env.getSource()");
        assertThat(fetchers).doesNotContain("env.getObject()");
    }

    @Test
    void build_errorTypeResolversAlphabeticallyOrdered_beforeSchemaBuilder() {
        // The @error TypeResolver loop preserves the existing ordering invariant: registrations
        // happen alphabetically and before .query()/.codeRegistry() seal the schema.
        var body = buildBody(ERROR_UNION_SDL);
        int sakErrorIdx = body.indexOf("codeRegistry.typeResolver(\"SakError\"");
        int queryIdx = body.indexOf(".query(");
        assertThat(sakErrorIdx).isGreaterThanOrEqualTo(0);
        assertThat(sakErrorIdx).as("typeResolvers before .query()").isLessThan(queryIdx);
    }

    // ===== federation overload =====

    @Test
    void nonFederation_emitsSingleBuildMethod() {
        var spec = generate("type Query { x: String }");
        assertThat(publicBuilds(spec)).hasSize(1);
    }

    @Test
    void federation_emitsTwoMethodsWhenFederationLinkTrue() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var spec = GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0);
        assertThat(publicBuilds(spec)).extracting(MethodSpec::name)
            .containsExactly("build", "build");
    }

    @Test
    void federation_oneArgMethodDelegatesToTwoArgForm() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var builds = publicBuilds(GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0));
        var oneArg = builds.get(0);
        assertThat(oneArg.parameters()).hasSize(1);
        assertThat(oneArg.code().toString()).contains("return build(customizer, fed -> {})");
    }

    @Test
    void federation_twoArgBodyCallsFederationTransform() {
        var bundle = TestSchemaHelper.buildBundle("type Query { x: String }");
        var builds = publicBuilds(GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0));
        var twoArg = builds.get(1);
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
        var builds = publicBuilds(GraphitronSchemaClassGenerator.generate(
            bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG, true).get(0));
        var body = builds.get(1).code().toString();
        int customizerIdx = body.indexOf("federationCustomizer.accept(fb)");
        int buildIdx = body.indexOf("return fb.build()");
        assertThat(customizerIdx).isGreaterThan(0).isLessThan(buildIdx);
    }

    // ===== Phase 2: scalar registration via the resolver =====

    @Test
    void scalarRegistration_specBuiltIns_emitOneAdditionalTypePerReferencedScalar() {
        // A fixture using all five spec built-ins must produce the same five .additionalType
        // lines the pre-resolver literal block emitted. Anchors the "no regression for
        // consumers using only spec built-ins" claim.
        var body = buildBody("""
            type Query {
                i: Int
                f: Float
                s: String
                b: Boolean
                id: ID
            }
            """);
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLInt)");
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLFloat)");
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLString)");
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLBoolean)");
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLID)");
    }

    @Test
    void scalarRegistration_directiveResolvesConsumerScalar_emitsAdditionalTypeForConstant() {
        // A scalar declaring @scalarType(scalar: "...FQN.FIELD") generates an additionalType
        // call pointing at the consumer's constant, in addition to the spec built-ins the
        // schema references.
        var body = buildBody("""
            scalar Money @scalarType(scalar: "no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY")
            type Query { m: Money, s: String }
            """);
        assertThat(body).contains(
            ".additionalType(no.sikt.graphitron.rewrite.scalarfixture.ScalarConstants.MONEY)");
        // Spec built-in still emitted.
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLString)");
    }

    @Test
    void scalarRegistration_directiveAliasesConstant_synthesisesScalarUnderSdlName() {
        // When the SDL name differs from the constant's intrinsic name (here
        // ExtendedScalars.Date is named "Date" but the scalar is declared as LocalDate), the
        // scalar must register under the SDL name, otherwise typeRef("LocalDate") fails at build.
        // The emitter routes this through the Synthesised arm + scalar_<name>() helper, the same
        // path federation-namespace scalars use, rather than additionalType(ExtendedScalars.Date).
        var body = buildBody("""
            scalar LocalDate @scalarType(scalar: "graphql.scalars.ExtendedScalars.Date")
            type Query { d: LocalDate, s: String }
            """);
        // Registered via the synthesised-scalar helper, not the bare constant.
        assertThat(body).contains(".additionalType(scalar_LocalDate())");
        assertThat(body).doesNotContain(".additionalType(graphql.scalars.ExtendedScalars.Date)");
        // The helper registers under the SDL name and borrows the Date constant's coercing.
        assertThat(body).contains("b.name(\"LocalDate\")");
        assertThat(body).contains("b.coercing(graphql.scalars.ExtendedScalars.Date.getCoercing())");
    }

    @Test
    void scalarRegistration_unresolvedNonSpecScalar_emitsNoAdditionalType() {
        // A non-spec, non-federation scalar without @scalarType is left unclassified. The schema
        // generator must not emit an additionalType for it.
        var body = buildBody("""
            scalar Money
            type Query { s: String, m: Money }
            """);
        assertThat(body).doesNotContain(".additionalType(no.sikt.graphitron.rewrite.scalarfixture.");
    }

    @Test
    void scalarRegistration_unreferencedSpecBuiltInIsNotEmitted() {
        // The pre-resolver literal block hardcoded all five spec built-ins regardless of usage. The
        // resolver-driven path only emits scalars the schema actually references. Graphql-java's
        // implicit introspection / directive surface pulls in Int, Boolean, and ID for any
        // schema, but Float has no implicit reference and is omitted when the SDL doesn't use it.
        var body = buildBody("type Query { x: String }");
        assertThat(body).contains(".additionalType(graphql.Scalars.GraphQLString)");
        assertThat(body).doesNotContain(".additionalType(graphql.Scalars.GraphQLFloat)");
    }

    private static TypeSpec generate(String sdl) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return GraphitronSchemaClassGenerator.generate(bundle.model(), bundle.assembled(), Set.of(), OUTPUT_PKG).get(0);
    }

    /**
     * Renders the full generated class. Substring assertions in this test cover both the
     * {@code build()} body and the private static helper methods the emitter
     * factors out (per-directive-definition, per-applied-directive, per-synthesised-scalar).
     */
    private static String buildBody(String sdl) {
        return generate(sdl).toString();
    }

    /** Renders the reified {@code <typeName>Fetchers} class for an @error type.*/
    private static String errorFetcherSource(String sdl, String typeName) {
        var bundle = TestSchemaHelper.buildBundle(sdl);
        return no.sikt.graphitron.rewrite.generators.util.ErrorTypeFetcherClassGenerator
            .generate(bundle.model(), OUTPUT_PKG).stream()
            .filter(t -> t.name().equals(typeName + "Fetchers"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + typeName + "Fetchers generated"))
            .toString();
    }

    /**
     * Returns the single {@code public static} {@code build} method on the schema class.
     * The class also carries {@code private static} helper methods that factor each
     * directive definition / applied directive / synthesised scalar out of the build body;
     * those are excluded here so the assertion focuses on the public surface.
     */
    private static MethodSpec publicBuild(TypeSpec spec) {
        return spec.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.PUBLIC))
            .filter(m -> "build".equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no public build method in " + spec.name()));
    }

    /**
     * Returns the {@code public static build} methods (one for non-federation, two for
     * federation: one-arg delegate + two-arg form), in declaration order.
     */
    private static List<MethodSpec> publicBuilds(TypeSpec spec) {
        return spec.methodSpecs().stream()
            .filter(m -> m.modifiers().contains(Modifier.PUBLIC))
            .filter(m -> "build".equals(m.name()))
            .toList();
    }
}
