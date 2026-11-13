package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TypeFetcherRenderTestSupport;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.TypeSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.Modifier;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for the tenant-routed {@code DSLContext} emission
 * ({@link TenantDslEmitter}): with a configured tenant column, each fetcher acquires per its
 * {@link no.sikt.graphitron.rewrite.model.TenantBinding} arm through the generated
 * {@code TenantConnections} carrier, and without one the emission keeps the exact
 * pre-tenant {@code graphitronContext(env).getDslContext(env)} form.
 *
 * <p>Fixtures ride the real catalog with {@code film_id} as the tenant column ({@code film} and
 * {@code inventory} carry it; {@code language} does not), mirroring
 * {@link no.sikt.graphitron.rewrite.TenantBindingClassificationTest}.
 */
@PipelineTier
class TenantRoutedFetcherPipelineTest {

    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path tmp;

    private static GraphitronSchema multiTenant(String sdl) {
        return TestSchemaHelper.buildSchema(
            sdl, TestConfiguration.testContext().withTenantColumn("film_id"));
    }

    private static String render(GraphitronSchema schema, String className, String methodName) {
        TypeSpec spec = TypeFetcherRenderTestSupport.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> className.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no generated class named " + className));
        return spec.methodSpecs().stream()
            .filter(m -> methodName.equals(m.name()))
            .findFirst()
            .map(MethodSpec::toString)
            .orElseThrow(() -> new AssertionError(
                "no method " + methodName + " on " + className + "; has: "
                    + spec.methodSpecs().stream().map(MethodSpec::name).toList()));
    }

    @Test
    void argumentBoundRootDivinesGuardsRoutesAndHandsDown() {
        var schema = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        var films = render(schema, "QueryFetchers", "films");
        assertThat(films)
            .contains("java.lang.Integer _divinedTenant = fake.code.generated.schema.TenantConnections.divinedTenant(env.<Object>getArgument(\"filmId\"))")
            .contains("org.jooq.DSLContext dsl = fake.code.generated.schema.TenantConnections.dslFor(env, _divinedTenant)")
            .contains(".localContext(fake.code.generated.schema.ConnectionKey.forTenant(_divinedTenant))")
            .doesNotContain("getDslContext(env)");
    }

    @Test
    void coBindingsAllReadIntoTheRuntimeAgreementGuard() {
        var schema = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id"),
                      altFilm: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "QueryFetchers", "films"))
            .contains("divinedTenant(env.<Object>getArgument(\"filmId\"), env.<Object>getArgument(\"altFilm\"))");
    }

    // ===== @service roots: divine before the call, route the call, stamp the return =====

    private static final String TENANT_SERVICE = "no.sikt.graphitron.rewrite.TenantServiceStub";

    private static final String SERVICE_TYPES = """
        interface Node { id: ID! }
        type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
            id: ID! @nodeId
            title: String
        }
        type RateFilmsPayload { films: [Film!]! }
        input RateFilmInput { film: ID! @nodeId(typeName: "Film") }
        type Query { x: String }
        """;

    private static String service(String method) {
        return "@service(service: {className: \"" + TENANT_SERVICE + "\", method: \"" + method + "\"})";
    }

    /**
     * Every divining service root shape declares the decode helper its tenant read goes through:
     * the plain and error-channel returns, with and without a {@code DSLContext} parameter, and
     * the table-returning root whose re-selection the divined tenant routes. That the tenant is
     * divined before the call, routes it and rides down as {@code localContext} is behaviour, and
     * {@code TenantDivinedRoutingExecutionTest} pins it against the multi-tenant fixture.
     */
    @Test
    void divingServiceRootsDeclareTheTenantSlotHelperOnTheirFetcherClass() {
        var shapes = java.util.Map.of(
            "rateFilm", "rateFilm(in: RateFilmInput!): RateFilmsPayload " + service("rateFilm"),
            "rateFilmWithDsl", "rateFilmWithDsl(in: RateFilmInput!): RateFilmsPayload " + service("rateFilmWithDsl"),
            "rateFilmOutcome", "rateFilmOutcome(in: RateFilmInput!): SakPayload " + service("rateFilmOutcome"),
            "pickFilm", "pickFilm(in: RateFilmInput!): Film " + service("pickFilm"));
        shapes.forEach((name, field) -> {
            var sdl = SERVICE_TYPES + """
                type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
                union RateError = DbErr
                type SakPayload { data: String errors: [RateError] }
                type Mutation { %s }
                """.formatted(field);
            var schema = multiTenant(sdl);
            assertThat(schema.tenantBindingOf("Mutation", name)).as(name)
                .isInstanceOf(no.sikt.graphitron.rewrite.model.TenantBinding.ArgumentBound.class);
            assertIsTenantSlotHelper(storeBackedMethod(tmp.resolve(name), sdl, "MutationFetchers",
                "decodeFilmTenantSlot0OrThrow"));
        });
    }

    /**
     * A tenant-routed DML payload with an errors field: the tenant is divined from the decoded ids
     * and handed down, and the errors ride the typed {@code Outcome} the fetcher returns, so the
     * two never share graphql-java's one {@code localContext} slot. That the success arm returns
     * the rows and the error arm the typed errors is behaviour, pinned by
     * {@code TenantDivinedRoutingExecutionTest}'s {@code dmlPayload_onTheErrorChannel_*} cases.
     */
    @Test
    void tenantRoutedDmlPayloadWithErrorsRidesTheOutcomeTransport() {
        var sdl = """
            interface Node { id: ID! }
            type Film implements Node @table(name: "film") @node(keyColumns: ["film_id"]) {
                id: ID! @nodeId
                title: String
            }
            input RetitleFilmInput { id: ID! @nodeId(typeName: "Film") title: String @field(name: "title") }
            type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
            union WriteError = DbErr
            type FilmsRetitledPayload { films: [Film] errors: [WriteError] }
            type Query { x: String }
            type Mutation {
                retitleFilms(in: [RetitleFilmInput!]!): FilmsRetitledPayload @mutation(typeName: UPDATE, table: "film")
            }
            """;
        var schema = multiTenant(sdl);

        assertThat(schema.tenantBindingOf("Mutation", "retitleFilms"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.TenantBinding.ArgumentBound.class);
        assertThat(((no.sikt.graphitron.rewrite.model.MutationField.MutationBulkDmlRecordField)
                schema.field("Mutation", "retitleFilms")).errorChannel())
            .get().isInstanceOf(no.sikt.graphitron.rewrite.model.ErrorChannel.Mapped.class);
        assertThat(((no.sikt.graphitron.rewrite.model.ChildField.ErrorsField)
                schema.field("FilmsRetitledPayload", "errors"))).isInstanceOf(no.sikt.graphitron.rewrite.model.ChildField.ErrorsField.class);
        assertReturnsOutcome(storeBackedMethod(tmp, sdl, "MutationFetchers", "retitleFilms"));
    }

    /** {@code DataFetcherResult<Outcome<…>>}: the fetcher's declared value is the typed wrapper. */
    static void assertReturnsOutcome(MethodSpec fetcher) {
        assertThat(fetcher.returnType())
            .isInstanceOfSatisfying(com.palantir.javapoet.ParameterizedTypeName.class, result -> {
                assertThat(result.rawType().canonicalName()).isEqualTo("graphql.execution.DataFetcherResult");
                assertThat(result.typeArguments()).singleElement()
                    .isInstanceOfSatisfying(com.palantir.javapoet.ParameterizedTypeName.class,
                        outcome -> assertThat(outcome.rawType().canonicalName())
                            .isEqualTo(DEFAULT_OUTPUT_PACKAGE + ".schema.Outcome"));
            });
    }

    @Test
    void inheritedChildRowsMethodReadsTheHandedDownTenant() {
        var schema = multiTenant("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "rowsInventories"))
            .contains("dslFor(env, fake.code.generated.schema.TenantConnections.divinedTenant(fake.code.generated.schema.ConnectionKey.tenantIn(env.getLocalContext())))")
            .doesNotContain("getDslContext(env)");
    }

    @Test
    void insertMutationDivinesFromItsInputFieldAndRoutes() {
        var schema = multiTenant("""
            type Inventory @table(name: "inventory") { inventoryId: Int @field(name: "inventory_id") }
            input InventoryCreateInput {
                filmId: Int! @field(name: "film_id")
                storeId: Int! @field(name: "store_id")
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                createInventory(in: InventoryCreateInput!): Inventory @mutation(typeName: INSERT)
            }
            """);

        assertThat(render(schema, "MutationFetchers", "createInventory"))
            .contains("divinedTenant(fake.code.generated.schema.TenantConnections.tenantSlot(env.getArgument(\"in\"), \"filmId\"))")
            .contains("dslFor(env, _divinedTenant)")
            .contains(".localContext(fake.code.generated.schema.ConnectionKey.forTenant(_divinedTenant))")
            .doesNotContain("getDslContext(env)");
    }

    /**
     * A routed write keyed by a node id acquires through the per-class decode helper the
     * registry mints for the slot projection. The pin is structural: the helper is declared on the
     * fetcher class that routes on it, with the one {@code Object} in and {@code Object} out the
     * divining fold's walk hands it (a dropped registration would compile here and fail at the
     * consumer). That the call site reads the id through it, and that the routed statement runs
     * on the decoded tenant, is the compilation and execution tiers' to show.
     */
    @Test
    void deleteKeyedByNodeIdDeclaresTheTenantSlotHelperOnItsFetcherClass() {
        var schema = multiTenant("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            type Mutation {
                deleteFilmActors(in: [DeleteFilmActorInput!]!): [ID!]!
                    @mutation(typeName: DELETE, table: "film_actor")
            }
            input DeleteFilmActorInput { id: ID! @nodeId(typeName: "FilmActor") }
            """);

        assertIsTenantSlotHelper(
            method(schema, "MutationFetchers", "decodeFilmActorTenantSlot1OrThrow"));
    }

    @Test
    void nodeIdFilteredReadDeclaresTheTenantSlotHelperOnItsFetcherClass() {
        var schema = multiTenant("""
            type FilmActor implements Node @table(name: "film_actor")
                    @node(keyColumns: ["actor_id", "film_id"]) {
                id: ID! @nodeId
            }
            type Query {
                filmActorsByNodeId(ids: [ID!]! @nodeId(typeName: "FilmActor")): [FilmActor!]!
            }
            """);

        assertIsTenantSlotHelper(
            method(schema, "QueryFetchers", "decodeFilmActorTenantSlot1OrThrow"));
    }

    /**
     * A method of the class a multi-tenant run over {@code sdl} generates from its store, for a
     * fixture whose producer catches into an outcome: its catch arm names the outcome type's table
     * from the store, which a store-less plan has none of.
     */
    private static MethodSpec storeBackedMethod(java.nio.file.Path directory, String sdl,
            String className, String methodName) {
        return methodOf(TestSchemaHelper.storeBackedFetchers(directory, sdl,
            TestConfiguration.testContext().withTenantColumn("film_id")), className, methodName);
    }

    private static MethodSpec method(GraphitronSchema schema, String className, String methodName) {
        return methodOf(TypeFetcherRenderTestSupport.generate(schema, DEFAULT_OUTPUT_PACKAGE), className,
            methodName);
    }

    private static MethodSpec methodOf(java.util.List<TypeSpec> specs, String className, String methodName) {
        TypeSpec spec = specs.stream()
            .filter(t -> className.equals(t.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no generated class named " + className));
        return spec.methodSpecs().stream()
            .filter(m -> methodName.equals(m.name()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no method " + methodName + " on " + className + "; has: "
                    + spec.methodSpecs().stream().map(MethodSpec::name).toList()));
    }

    private static void assertIsTenantSlotHelper(MethodSpec helper) {
        assertThat(helper.returnType()).isEqualTo(ClassName.OBJECT);
        assertThat(helper.parameters())
            .singleElement()
            .satisfies(p -> assertThat(p.type()).isEqualTo(ClassName.OBJECT));
        assertThat(helper.modifiers()).contains(Modifier.PRIVATE, Modifier.STATIC);
    }

    @Test
    void inheritedBatchedChildPartitionsItsLoaderNamePerTenant() {
        var schema = multiTenant("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "inventories"))
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.tenantLoaderName(env, \"Film\");")
            .doesNotContain("String.join");
    }

    /**
     * The discriminated interface child's batched half partitions its loader the same way, and
     * the assertion is on the emitted <em>name expression</em> rather than on the presence of a
     * loader: a batch loader resolves one {@code DSLContext} from the environment captured at
     * loader creation, so a tenant-mixed batch would execute every key against the first key's
     * tenant and serve one tenant's rows to another. No verdict-level fact can see that, and the
     * name is passed per emission site rather than structurally forced, so this is the check.
     */
    @Test
    void inheritedBatchedDiscriminatedInterfaceChildPartitionsItsLoaderNamePerTenant() {
        var schema = multiTenant("""
            interface Content @table(name: "content") @discriminate(on: "CONTENT_TYPE") {
                title: String @field(name: "TITLE")
            }
            type FilmContent implements Content @table(name: "content") @discriminator(value: "FILM") {
                title: String @field(name: "TITLE")
            }
            type Film @table(name: "film") {
                title: String
                contents: [Content!]! @reference(path: [{key: "content_film_id_fkey"}])
            }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "contents"))
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.tenantLoaderName(env, \"Film\");")
            .doesNotContain("String.join");
    }

    @Test
    void untenantedBatchedChildKeepsTheBarePathNameThroughTheSharedSeam() {
        var schema = multiTenant("""
            type Customer @table(name: "customer") {
                email: String
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
            }
            type Address @table(name: "address") { postalCode: String @field(name: "postal_code") }
            type Query { customers: [Customer!]! }
            """);

        assertThat(render(schema, "CustomerFetchers", "address"))
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.loaderName(env, \"Customer\");")
            .doesNotContain("tenantLoaderName");
    }

    @Test
    void singleTenantLoaderNameKeepsTheInlinePathJoin() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") {
                title: String
                inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Inventory @table(name: "inventory") { inventoryId: Int }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """, TestConfiguration.testContext());

        assertThat(render(schema, "FilmFetchers", "inventories"))
            .contains("java.lang.String name = java.lang.String.join(\"/\", env.getExecutionStepInfo().getPath().getKeysOnly()) + \"@Film\";")
            .doesNotContain("TenantConnections");
    }

    @Test
    void singleTenantEmissionKeepsTheEscapeHatchFormByteForByte() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int! @field(name: "film_id")): [Film!]!
            }
            """, TestConfiguration.testContext());

        var films = render(schema, "QueryFetchers", "films");
        assertThat(films)
            .contains("org.jooq.DSLContext dsl = graphitronContext(env).getDslContext(env);")
            .doesNotContain("TenantConnections")
            .doesNotContain("localContext");
    }

    // ===== A nesting type's fields route on the binding the fold assigned them =====

    private static final String FILM_STOCK = """
        type Film @table(name: "film") {
            title: String
            stock: FilmStock
        }
        type FilmStock {
            inventories: [Inventory!]! @splitQuery @reference(path: [{key: "inventory_film_id_fkey"}])
            inventoriesConnection: [Inventory!]! @asConnection @splitQuery
                @reference(path: [{key: "inventory_film_id_fkey"}]) @defaultOrder(primaryKey: true)
            scenesWhere(filter: FilmSceneFilter): [FilmScene!]! @splitQuery
                @reference(path: [{key: "film_scene_film_id_fkey"}])
        }
        type Inventory @table(name: "inventory") { inventoryId: Int }
        type FilmScene implements Node @table(name: "film_scene")
                @node(keyColumns: ["film_id", "scene_no"]) {
            id: ID! @nodeId
            label: String @field(name: "label")
        }
        input FilmSceneFilter { scene: ID @nodeId(typeName: "FilmScene") }
        type Query {
            films(filmId: Int! @field(name: "film_id")): [Film!]!
        }
        """;

    private static no.sikt.graphitron.plan.LauncherRelation nestedLaunchers(GraphitronSchema schema,
                                                                             String nestingType) {
        var run = no.sikt.graphitron.plan.LauncherCommands.produce(schema,
            no.sikt.graphitron.plan.ConditionCommands.produce(schema, DEFAULT_OUTPUT_PACKAGE),
            DEFAULT_OUTPUT_PACKAGE, null);
        java.util.List<no.sikt.graphitron.rewrite.model.GraphitronField> nested =
            java.util.List.copyOf(schema.nestingReach().wiringFor(nestingType).nestedFields());
        return TypeFetcherGenerator.nestingReachedLaunchers(nested, DEFAULT_OUTPUT_PACKAGE, run, null);
    }

    @Test
    void aNestingTypesBatchedFieldsCarryTheirFoldedArm() {
        var schema = multiTenant(FILM_STOCK);

        // scenesWhere's filter names the tenant column, but under a parent that already has a
        // tenant it only filters: the field routes exactly as its argument-free siblings do.
        for (var field : java.util.List.of("inventories", "inventoriesConnection", "scenesWhere")) {
            assertThat(schema.tenantBindingOf("FilmStock", field)).as(field)
                .isEqualTo(new no.sikt.graphitron.rewrite.model.TenantBinding.Inherited("FilmStock"));
        }
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    @Test
    void aNestingTypeUnderAGlobalParentCarriesTheParentRowArm() {
        var schema = multiTenant("""
            type FilmEndorsement @table(name: "film_endorsement") {
                note: String
                about: EndorsementAbout
            }
            type EndorsementAbout {
                film: Film @splitQuery
            }
            type Film @table(name: "film") { title: String }
            type Query { endorsements: [FilmEndorsement!]! }
            """);

        assertThat(schema.tenantBindingOf("EndorsementAbout", "film"))
            .isInstanceOf(no.sikt.graphitron.rewrite.model.TenantBinding.ParentRowBound.class);
        assertThat(schema.tenantBindings().rejections()).isEmpty();
    }

    /**
     * The nested class reads the run's one carrier-routing fact, so a nesting type's rows method
     * builds its connections over the routed source it paged on, as a class of a {@code @table}
     * type does in the same build. That the count closure then aggregates on that source is
     * execution's: TenantDivinedRoutingExecutionTest's
     * nestedInheritedConnection_totalCountAggregatesOnTheDivinedTenantSource.
     */
    @Test
    void aNestingTypesClassRoutesItsCarriersExactlyAsTheRunDoes() {
        var routed = multiTenant(FILM_STOCK);
        assertThat(nestedLaunchers(routed, "FilmStock").carrierDsl())
            .isEqualTo(no.sikt.graphitron.command.CarrierDsl.ROUTED);

        var single = TestSchemaHelper.buildSchema(FILM_STOCK);
        assertThat(nestedLaunchers(single, "FilmStock").carrierDsl())
            .isEqualTo(no.sikt.graphitron.command.CarrierDsl.ENV_ACQUIRED);
    }

    /**
     * Under configured scopes a routing site never falls back to the default source: a coordinate
     * the fold assigned no binding is a generator bug, named, rather than a silent read on the
     * fixed default source.
     */
    @Test
    void aRoutingSiteForACoordinateTheFoldMissedFailsNamingIt() {
        var schema = multiTenant(FILM_STOCK);
        var ctx = new TypeFetcherEmissionContext("FilmStock", schema);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> TenantDslEmitter.resolveByName(ctx, "unassigned", DEFAULT_OUTPUT_PACKAGE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'FilmStock.unassigned'")
            .hasMessageContaining("assigned it no binding");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> TenantDslEmitter.loaderNameDeclaration(ctx, "unassigned", "name", DEFAULT_OUTPUT_PACKAGE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("'FilmStock.unassigned'");
    }
}
