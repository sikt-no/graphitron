package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TypeFetcherRenderTestSupport;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeName;
import no.sikt.graphitron.javapoet.TypeSpec;
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
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        var films = render(schema, "QueryFetchers", "films");
        assertThat(films)
            .contains("java.lang.Integer _divinedTenant = fake.code.generated.schema.TenantConnections.divinedTenant(env.<Object>getArgument(\"filmId\"))")
            .contains("org.jooq.DSLContext dsl = fake.code.generated.schema.TenantConnections.dslFor(env, _divinedTenant)")
            .contains(".localContext(_divinedTenant)")
            .doesNotContain("getDslContext(env)");
    }

    @Test
    void coBindingsAllReadIntoTheRuntimeAgreementGuard() {
        var schema = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id"),
                      altFilm: Int @field(name: "film_id")): [Film!]!
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
            var schema = multiTenant(SERVICE_TYPES + """
                type DbErr @error(handlers: [{handler: DATABASE}]) { path: [String!]! message: String! }
                union RateError = DbErr
                type SakPayload { data: String errors: [RateError] }
                type Mutation { %s }
                """.formatted(field));
            assertThat(schema.tenantBindingOf("Mutation", name)).as(name)
                .isInstanceOf(no.sikt.graphitron.rewrite.model.TenantBinding.ArgumentBound.class);
            assertIsTenantSlotHelper(method(schema, "MutationFetchers", "decodeFilmTenantSlot0OrThrow"));
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
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "rowsInventories"))
            .contains("dslFor(env, fake.code.generated.schema.TenantConnections.divinedTenant(env.<Object>getLocalContext()))")
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
            .contains(".localContext(_divinedTenant)")
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
                filmActorsByNodeId(ids: [ID!] @nodeId(typeName: "FilmActor")): [FilmActor!]!
            }
            """);

        assertIsTenantSlotHelper(
            method(schema, "QueryFetchers", "decodeFilmActorTenantSlot1OrThrow"));
    }

    private static MethodSpec method(GraphitronSchema schema, String className, String methodName) {
        TypeSpec spec = TypeFetcherRenderTestSupport.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
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
        assertThat(helper.returnType()).isEqualTo(TypeName.OBJECT);
        assertThat(helper.parameters())
            .singleElement()
            .satisfies(p -> assertThat(p.type()).isEqualTo(TypeName.OBJECT));
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
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "inventories"))
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.tenantLoaderName(env);")
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
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        assertThat(render(schema, "FilmFetchers", "contents"))
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.tenantLoaderName(env);")
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
            .contains("java.lang.String name = fake.code.generated.schema.TenantConnections.loaderName(env);")
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
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """, TestConfiguration.testContext());

        assertThat(render(schema, "FilmFetchers", "inventories"))
            .contains("java.lang.String name = java.lang.String.join(\"/\", env.getExecutionStepInfo().getPath().getKeysOnly());")
            .doesNotContain("TenantConnections");
    }

    private static String renderHandle(GraphitronSchema schema, String typeName) {
        TypeSpec dispatch = no.sikt.graphitron.rewrite.generators.util.EntityFetcherDispatchClassGenerator
            .generate(schema, DEFAULT_OUTPUT_PACKAGE).get(0);
        return dispatch.methodSpecs().stream()
            .filter(m -> ("handle" + typeName).equals(m.name()))
            .findFirst()
            .map(MethodSpec::toString)
            .orElseThrow();
    }

    private static final String FILM_ACTOR_NODE_SDL = """
        type FilmActor implements Node @table(name: "film_actor")
                @node(keyColumns: ["actor_id", "film_id"]) {
            id: ID! @nodeId
        }
        type Query {
            node(id: ID!): Node
        }
        """;

    @Test
    void tenantScopedDispatchGroupsPerTenantAtTheDecodedPosition() {
        var handle = renderHandle(multiTenant(FILM_ACTOR_NODE_SDL), "FilmActor");
        assertThat(handle)
            // Grouping widened to altIndex -> tenantKey -> bindings; the tenant reads at the
            // classified decoded position (film_id is position 1 of the FilmActor key).
            .contains("java.util.Map<java.lang.Integer, java.util.Map<java.lang.Object, java.util.List<java.lang.Object[]>>> groups")
            .contains(".computeIfAbsent(cols[1], k -> new java.util.ArrayList<>())")
            .doesNotContain("getDslContext(groupEnv)");
        // Each group acquires its own tenant, and a group outside the request tenant set is
        // skipped before acquisition so its positions stay null: pinned where it runs, in
        // TenantDivinedRoutingExecutionTest's node and nodes cases.
    }

    @Test
    void directKeyEntityDispatchGroupsPerTenantAtTheRepFieldPosition() {
        // A federation @key entity decodes representation FIELD VALUES (a Direct alternative),
        // not a synthesised node id; the tenant reads at the classified position of that decode.
        var schema = multiTenant("""
            directive @key(fields: String!, resolvable: Boolean = true) repeatable on OBJECT | INTERFACE
            type FilmActor @table(name: "film_actor") @key(fields: "actorId filmId") {
                actorId: Int @field(name: "actor_id")
                filmId: Int @field(name: "film_id")
            }
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            """);

        var handle = renderHandle(schema, "FilmActor");
        assertThat(handle)
            .contains("cols[1] = rep.get(\"filmId\")")
            .contains(".computeIfAbsent(cols[1], ")
            .contains("dslFor(groupEnv, ")
            .doesNotContain("getDslContext(groupEnv)");
    }

    @Test
    void singleTenantDispatchKeepsTheEscapeHatchGrouping() {
        var handle = renderHandle(TestSchemaHelper.buildSchema(
            FILM_ACTOR_NODE_SDL, TestConfiguration.testContext()), "FilmActor");
        assertThat(handle)
            .contains("java.util.Map<java.lang.Integer, java.util.List<java.lang.Object[]>> groups")
            .contains("org.jooq.DSLContext dsl = graphitronContext(groupEnv).getDslContext(groupEnv);")
            .doesNotContain("TenantConnections");
    }

    @Test
    void singleTenantEmissionKeepsTheEscapeHatchFormByteForByte() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """, TestConfiguration.testContext());

        var films = render(schema, "QueryFetchers", "films");
        assertThat(films)
            .contains("org.jooq.DSLContext dsl = graphitronContext(env).getDslContext(env);")
            .doesNotContain("TenantConnections")
            .doesNotContain("localContext");
    }
}
