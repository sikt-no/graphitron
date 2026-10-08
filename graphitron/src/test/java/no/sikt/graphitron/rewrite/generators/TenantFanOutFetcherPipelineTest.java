package no.sikt.graphitron.rewrite.generators;

import no.sikt.graphitron.rewrite.TypeFetcherRenderTestSupport;
import no.sikt.graphitron.javapoet.MethodSpec;
import no.sikt.graphitron.javapoet.TypeSpec;
import no.sikt.graphitron.common.configuration.TestConfiguration;
import no.sikt.graphitron.rewrite.GraphitronSchema;
import no.sikt.graphitron.rewrite.TestSchemaHelper;
import no.sikt.graphitron.rewrite.generators.schema.GraphitronFacadeGenerator;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pipeline coverage for the fanned-fetcher emission (fields classified
 * {@link no.sikt.graphitron.rewrite.model.TenantBinding.FanOut}) and the factory's dedicated
 * request-tenant-set parameter, as {@code TypeSpec}-structure assertions; the collapse
 * behaviour (null placement, error path, union order) is pinned at the execution tier per the
 * behaviour-above-pipeline discipline. Fixtures ride the real catalog with {@code film_id} as
 * the tenant column, mirroring {@link TenantRoutedFetcherPipelineTest}.
 */
@PipelineTier
class TenantFanOutFetcherPipelineTest {

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

    /** The facade's factory methods by name, each overload rendered as its parameter list ({@code type name}). */
    private static java.util.Map<String, java.util.List<java.util.List<String>>> factoryParameters(GraphitronSchema schema) {
        TypeSpec facade = GraphitronFacadeGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> GraphitronFacadeGenerator.CLASS_NAME.equals(t.name()))
            .findFirst()
            .orElseThrow();
        return facade.methodSpecs().stream()
            .filter(m -> m.name().equals("newExecutionInput") || m.name().equals("newOwnedExecutionInput"))
            .collect(java.util.stream.Collectors.groupingBy(MethodSpec::name,
                java.util.stream.Collectors.mapping(
                    m -> m.parameters().stream().map(p -> p.type() + " " + p.name()).toList(),
                    java.util.stream.Collectors.toList())));
    }

    @Test
    void fannedRootFieldScattersItsOwnStatementAndCollapsesTheUnion() {
        var schema = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);

        // The composition lives in the fanned launcher unit; the thin entry point collapses the
        // scatter's outcome list. The selection-set projection is hoisted onto the dispatch
        // thread inside the launcher, so the per-tenant lambda touches only its own DSLContext
        // and pre-computed locals, never env.
        var entry = render(schema, "QueryFetchers", "films");
        assertThat(entry)
            .contains("fake.code.generated.schema.TenantConnections.collapseFanOut(env,")
            .contains("QueryFetchers.rowsFilms(env)")
            // No single-DSL acquisition and no single hand-down local: tenants ride per element.
            .doesNotContain("getDslContext(env)")
            .doesNotContain("_divinedTenant");
        var launcher = render(schema, "QueryFetchers", "rowsFilms");
        assertThat(launcher)
            .contains("fake.code.generated.schema.TenantConnections.fanOutRows(env, dsl -> dsl")
            .contains("selectFields = fake.code.generated.types.Film.$project(env.getSelectionSet().getFieldsGroupedByResultKey(), filmTable, env)")
            .contains(".select(selectFields)")
            .contains(".where(condition)")
            .contains(".orderBy(orderBy)")
            .doesNotContain("getDslContext(env)")
            .doesNotContain("_divinedTenant");
    }

    @Test
    void fannedChildOfUntenantedParentForcesTheFetcherBoundary_batchedForm() {
        // No @splitQuery in the SDL: the marker itself forces the boundary, so the field
        // classifies batched and the rows method scatters once per parent batch.
        var schema = multiTenant("""
            type Language @table(name: "language") {
                name: String
                films: [Film] @reference(path: [{key: "film_language_id_fkey"}]) @tenantFanOut
            }
            type Film @table(name: "film") { title: String }
            type Query { languages: [Language!]! }
            """);

        var fetcher = render(schema, "LanguageFetchers", "films");
        assertThat(fetcher)
            // The fanned batched fetcher registers under the bare path name (parents are
            // untenanted; the fan-out happens inside the batch load) and collapses each parent's
            // marker-bearing list against that parent's own env.
            .contains("fake.code.generated.schema.TenantConnections.loaderName(env)")
            .contains(".thenApply(payload -> fake.code.generated.schema.TenantConnections.collapseFanOut(env, payload))")
            .doesNotContain("tenantLoaderName");

        var rows = render(schema, "LanguageFetchers", "rowsFilms");
        assertThat(rows)
            // One scatter per parent batch, one statement per tenant per batch: the batch
            // statement is the perTenant unit of work, and no per-method DSL declaration exists.
            .contains("fake.code.generated.schema.TenantConnections.fanOutBatchRows(env, keys.size(), dsl -> {")
            .contains("return scatterByIdx(flat, keys.size());")
            .doesNotContain("org.jooq.DSLContext dsl =");
    }

    @Test
    void factoriesCarryTheRequestTenantSetInEveryMultiTenantBuild() {
        var fanned = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film] @tenantFanOut }
            """);
        // A routed-only schema has no fanned field, and still carries the set: routing checks
        // every divined tenant against it, and the gate is the build's tenant column, never an
        // evolving schema property on a public factory signature.
        var routedOnly = multiTenant("""
            type Film @table(name: "film") { title: String }
            type Query {
                films(filmId: Int @field(name: "film_id")): [Film!]!
            }
            """);

        for (var schema : java.util.List.of(fanned, routedOnly)) {
            // Both factory forms carry the dedicated typed slot, so a missing or mis-typed set is
            // a compile error at the call site. The owned form also takes the typed
            // RequestTenants value, which can name a default tenant; the escape hatch, which has
            // no carrier to read one, does not.
            var factories = factoryParameters(schema);
            assertThat(factories.get("newExecutionInput")).containsExactly(java.util.List.of(
                "org.jooq.DSLContext defaultDsl", "java.util.Collection<java.lang.Integer> tenants"));
            assertThat(factories.get("newOwnedExecutionInput")).containsExactlyInAnyOrder(
                java.util.List.of("java.util.Collection<java.lang.Integer> tenants"),
                java.util.List.of(DEFAULT_OUTPUT_PACKAGE + ".schema.RequestTenants requestTenants"));
        }
    }

    @Test
    void factoriesOmitTheRequestTenantSetInASingleTenantBuild() {
        var singleTenant = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { allFilms: [Film!]! }
            """);
        var factories = factoryParameters(singleTenant);
        assertThat(factories.get("newExecutionInput"))
            .containsExactly(java.util.List.of("org.jooq.DSLContext defaultDsl"));
        assertThat(factories.get("newOwnedExecutionInput")).containsExactly(java.util.List.of());
    }
}
