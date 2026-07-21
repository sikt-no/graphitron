package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

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
        TypeSpec spec = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
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

    @Test
    void untenantedRootAcquiresTheDefaultSourceAndHandsNothingDown() {
        var schema = multiTenant("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            """);

        var languages = render(schema, "QueryFetchers", "languages");
        assertThat(languages)
            .contains("org.jooq.DSLContext dsl = fake.code.generated.schema.TenantConnections.dslDefault(env)")
            .doesNotContain("localContext")
            .doesNotContain("getDslContext(env)");
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
            input InventoryCreateInput @table(name: "inventory") {
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
            .contains("dslFor(groupEnv, fake.code.generated.schema.TenantConnections.divinedTenant(tenantEntry.getKey()))")
            .doesNotContain("getDslContext(groupEnv)");
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
    void globalEntityDispatchAcquiresTheDefaultSourceInMultiTenantBuilds() {
        var handle = renderHandle(multiTenant("""
            type Language implements Node @table(name: "language") @node {
                id: ID! @nodeId
            }
            type Query {
                node(id: ID!): Node
            }
            """), "Language");
        assertThat(handle)
            .contains("fake.code.generated.schema.TenantConnections.dslDefault(groupEnv)")
            .doesNotContain("dslFor")
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
