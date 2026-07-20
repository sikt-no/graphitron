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
            .contains("var _divinedTenant = fake.code.generated.schema.TenantConnections.divinedTenant(env.getArgument(\"filmId\"))")
            .contains("org.jooq.DSLContext dsl = fake.code.generated.schema.TenantConnections.of(env).dslFor(_divinedTenant)")
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
            .contains("divinedTenant(env.getArgument(\"filmId\"), env.getArgument(\"altFilm\"))");
    }

    @Test
    void untenantedRootAcquiresTheDefaultSourceAndHandsNothingDown() {
        var schema = multiTenant("""
            type Language @table(name: "language") { name: String }
            type Query { languages: [Language!]! }
            """);

        var languages = render(schema, "QueryFetchers", "languages");
        assertThat(languages)
            .contains("org.jooq.DSLContext dsl = fake.code.generated.schema.TenantConnections.of(env).dslDefault()")
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
            .contains("dslFor(fake.code.generated.schema.TenantConnections.divinedTenant(env.getLocalContext()))")
            .doesNotContain("getDslContext(env)");
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
